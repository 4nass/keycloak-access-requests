package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import jakarta.persistence.EntityManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence operations for the transactional notification outbox.
 */
public final class JpaAccessRequestNotificationOutboxRepository {

    private static final int DELIVERY_KEY_LOOKUP_BATCH_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 100;

    private final EntityManager entityManager;

    public JpaAccessRequestNotificationOutboxRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public void enqueue(
            AccessRequestNotification notification,
            AccessRequestNotificationRecipientType recipientType,
            String recipientId,
            Instant queuedAt) {
        entityManager.persist(AccessRequestNotificationOutboxEntity.queue(
                notification, recipientType, recipientId, queuedAt));
    }

    /**
     * Persists deliveries only when no worker has already expanded the same role instruction.
     */
    public void enqueueIfAbsent(
            AccessRequestNotification notification,
            AccessRequestNotificationRecipientType recipientType,
            Collection<String> recipientIds,
            Instant queuedAt) {
        List<AccessRequestNotificationOutboxEntity> candidates = recipientIds.stream()
                .map(recipientId -> AccessRequestNotificationOutboxEntity.queue(
                        notification, recipientType, recipientId, queuedAt))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        Set<String> missingDeliveryKeys = new HashSet<>();
        for (AccessRequestNotificationOutboxEntity candidate : candidates) {
            missingDeliveryKeys.add(candidate.deliveryKey());
        }
        List<String> deliveryKeys = List.copyOf(missingDeliveryKeys);
        for (int start = 0; start < deliveryKeys.size(); start += DELIVERY_KEY_LOOKUP_BATCH_SIZE) {
            int end = Math.min(start + DELIVERY_KEY_LOOKUP_BATCH_SIZE, deliveryKeys.size());
            missingDeliveryKeys.removeAll(entityManager.createQuery("""
                            select entry.deliveryKey
                              from AccessRequestNotificationOutboxEntity entry
                             where entry.deliveryKey in :deliveryKeys
                            """, String.class)
                    .setParameter("deliveryKeys", deliveryKeys.subList(start, end))
                    .getResultList());
        }
        candidates.stream()
                .filter(candidate -> missingDeliveryKeys.contains(candidate.deliveryKey()))
                .forEach(entityManager::persist);
    }

    /**
     * Atomically leases due entries. Conditional updates make this safe when every Keycloak node runs a worker.
     */
    public List<AccessRequestNotificationOutboxEntity> claimDue(
            Instant now,
            Duration leaseDuration,
            String processorId,
            int batchSize) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        Objects.requireNonNull(processorId, "processorId must not be null");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        long nowTimestamp = now.toEpochMilli();
        long leaseUntilTimestamp = now.plus(leaseDuration).toEpochMilli();
        List<String> candidateIds = entityManager.createQuery("""
                        select entry.id
                          from AccessRequestNotificationOutboxEntity entry
                         where (entry.state = :pending and entry.nextAttemptTimestamp <= :now)
                            or (entry.state = :processing and entry.leaseUntilTimestamp <= :now)
                         order by entry.nextAttemptTimestamp asc, entry.id asc
                        """, String.class)
                .setParameter("pending", AccessRequestNotificationOutboxState.PENDING)
                .setParameter("processing", AccessRequestNotificationOutboxState.PROCESSING)
                .setParameter("now", nowTimestamp)
                .setMaxResults(batchSize)
                .getResultList();

        List<String> claimedIds = new ArrayList<>();
        for (String candidateId : candidateIds) {
            int claimed = entityManager.createQuery("""
                            update AccessRequestNotificationOutboxEntity entry
                               set entry.state = :processing,
                                   entry.processorId = :processorId,
                                   entry.leaseUntilTimestamp = :leaseUntil,
                                   entry.lastAttemptTimestamp = :now,
                                   entry.attemptCount = entry.attemptCount + 1
                             where entry.id = :id
                               and ((entry.state = :pending and entry.nextAttemptTimestamp <= :now)
                                 or (entry.state = :processing and entry.leaseUntilTimestamp <= :now))
                            """)
                    .setParameter("processing", AccessRequestNotificationOutboxState.PROCESSING)
                    .setParameter("processorId", processorId)
                    .setParameter("leaseUntil", leaseUntilTimestamp)
                    .setParameter("now", nowTimestamp)
                    .setParameter("id", candidateId)
                    .setParameter("pending", AccessRequestNotificationOutboxState.PENDING)
                    .executeUpdate();
            if (claimed == 1) {
                claimedIds.add(candidateId);
            }
        }
        entityManager.clear();
        return claimedIds.stream()
                .map(id -> entityManager.find(AccessRequestNotificationOutboxEntity.class, id))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Returns failed deliveries only. The administrative view deliberately excludes recipient e-mail addresses;
     * recipients are represented by their Keycloak identifiers.
     */
    public NotificationOutboxPage findFailed(String realmId, int page, int size) {
        String requiredRealmId = requireRealmId(realmId);
        int offset = validateOffset(page, size);
        long total = entityManager.createQuery("""
                        select count(entry)
                          from AccessRequestNotificationOutboxEntity entry
                         where entry.realmId = :realmId
                           and entry.state = :failed
                        """, Long.class)
                .setParameter("realmId", requiredRealmId)
                .setParameter("failed", AccessRequestNotificationOutboxState.FAILED)
                .getSingleResult();
        List<AccessRequestNotificationOutboxEntity> items = entityManager.createQuery("""
                        select entry
                           from AccessRequestNotificationOutboxEntity entry
                         where entry.realmId = :realmId
                           and entry.state = :failed
                         order by coalesce(entry.lastAttemptTimestamp, entry.nextAttemptTimestamp) desc, entry.id asc
                        """, AccessRequestNotificationOutboxEntity.class)
                .setParameter("realmId", requiredRealmId)
                .setParameter("failed", AccessRequestNotificationOutboxState.FAILED)
                .setFirstResult(offset)
                .setMaxResults(size)
                .getResultList();
        return new NotificationOutboxPage(items, page, size, total);
    }

    /**
     * Produces low-cardinality operational counts for one realm. These values are intentionally suitable for
     * the administration view and for an authenticated monitoring poller.
     */
    public NotificationOutboxSummary summarize(String realmId) {
        String requiredRealmId = requireRealmId(realmId);
        EnumMap<AccessRequestNotificationOutboxState, Long> counts =
                new EnumMap<>(AccessRequestNotificationOutboxState.class);
        for (AccessRequestNotificationOutboxState state : AccessRequestNotificationOutboxState.values()) {
            counts.put(state, 0L);
        }
        entityManager.createQuery("""
                        select entry.state, count(entry)
                          from AccessRequestNotificationOutboxEntity entry
                         where entry.realmId = :realmId
                         group by entry.state
                        """, Object[].class)
                .setParameter("realmId", requiredRealmId)
                .getResultList()
                .forEach(row -> counts.put((AccessRequestNotificationOutboxState) row[0], (Long) row[1]));
        return new NotificationOutboxSummary(
                counts.get(AccessRequestNotificationOutboxState.PENDING),
                counts.get(AccessRequestNotificationOutboxState.PROCESSING),
                counts.get(AccessRequestNotificationOutboxState.DELIVERED),
                counts.get(AccessRequestNotificationOutboxState.DISCARDED),
                counts.get(AccessRequestNotificationOutboxState.FAILED));
    }

    /**
     * Resets a terminal delivery to a new ten-attempt retry budget. The conditional state update ensures that
     * concurrent operators cannot requeue the same row twice.
     */
    public RetryFailedResult retryFailed(String realmId, String id, Instant retriedAt) {
        String requiredRealmId = requireRealmId(realmId);
        Objects.requireNonNull(retriedAt, "retriedAt must not be null");
        Optional<AccessRequestNotificationOutboxEntity> existing = findById(requiredRealmId, id);
        if (existing.isEmpty()) {
            return RetryFailedResult.NOT_FOUND;
        }
        int updated = entityManager.createQuery("""
                        update AccessRequestNotificationOutboxEntity entry
                           set entry.state = :pending,
                               entry.attemptCount = 0,
                               entry.nextAttemptTimestamp = :nextAttemptTimestamp,
                               entry.processorId = null,
                               entry.leaseUntilTimestamp = null,
                               entry.deliveredTimestamp = null
                         where entry.id = :id
                           and entry.realmId = :realmId
                           and entry.state = :failed
                        """)
                .setParameter("pending", AccessRequestNotificationOutboxState.PENDING)
                .setParameter("nextAttemptTimestamp", retriedAt.toEpochMilli())
                .setParameter("id", existing.get().id())
                .setParameter("realmId", requiredRealmId)
                .setParameter("failed", AccessRequestNotificationOutboxState.FAILED)
                .executeUpdate();
        return updated == 1 ? RetryFailedResult.RETRIED : RetryFailedResult.NOT_FAILED;
    }

    public void markDelivered(String id, String processorId, Instant deliveredAt) {
        transition(id, processorId, AccessRequestNotificationOutboxState.DELIVERED, deliveredAt, null);
    }

    public void markDiscarded(String id, String processorId, Instant discardedAt) {
        transition(id, processorId, AccessRequestNotificationOutboxState.DISCARDED, discardedAt, null);
    }

    public void markFailed(String id, String processorId, Instant now, int maximumAttempts) {
        AccessRequestNotificationOutboxEntity entry = entityManager.find(AccessRequestNotificationOutboxEntity.class, id);
        if (entry == null || entry.attemptCount() < 1) {
            return;
        }
        AccessRequestNotificationOutboxState state = entry.attemptCount() >= maximumAttempts
                ? AccessRequestNotificationOutboxState.FAILED
                : AccessRequestNotificationOutboxState.PENDING;
        Instant nextAttempt = state == AccessRequestNotificationOutboxState.FAILED
                ? null
                : now.plusSeconds(retryDelaySeconds(entry.attemptCount()));
        transition(id, processorId, state, now, nextAttempt);
    }

    private void transition(
            String id,
            String processorId,
            AccessRequestNotificationOutboxState state,
            Instant now,
            Instant nextAttempt) {
        entityManager.createQuery("""
                        update AccessRequestNotificationOutboxEntity entry
                           set entry.state = :state,
                               entry.processorId = null,
                               entry.leaseUntilTimestamp = null,
                               entry.deliveredTimestamp = :deliveredTimestamp,
                               entry.nextAttemptTimestamp = :nextAttemptTimestamp
                         where entry.id = :id
                           and entry.state = :processing
                           and entry.processorId = :processorId
                        """)
                .setParameter("state", state)
                .setParameter("deliveredTimestamp", state == AccessRequestNotificationOutboxState.DELIVERED
                        ? now.toEpochMilli()
                        : null)
                .setParameter("nextAttemptTimestamp", nextAttempt == null ? now.toEpochMilli() : nextAttempt.toEpochMilli())
                .setParameter("id", id)
                .setParameter("processing", AccessRequestNotificationOutboxState.PROCESSING)
                .setParameter("processorId", processorId)
                .executeUpdate();
    }

    private static long retryDelaySeconds(int attemptCount) {
        return 1L << Math.min(attemptCount, 6);
    }

    private Optional<AccessRequestNotificationOutboxEntity> findById(String realmId, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return entityManager.createQuery("""
                        select entry
                          from AccessRequestNotificationOutboxEntity entry
                         where entry.id = :id
                           and entry.realmId = :realmId
                        """, AccessRequestNotificationOutboxEntity.class)
                .setParameter("id", id)
                .setParameter("realmId", realmId)
                .getResultStream()
                .findFirst();
    }

    private static int validateOffset(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        try {
            return Math.multiplyExact(page, size);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("page and size are too large", exception);
        }
    }

    private static String requireRealmId(String realmId) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        if (realmId.isBlank()) {
            throw new IllegalArgumentException("realmId must not be blank");
        }
        return realmId;
    }

    public record NotificationOutboxPage(
            List<AccessRequestNotificationOutboxEntity> items,
            int page,
            int size,
            long total) {
    }

    public record NotificationOutboxSummary(
            long pending,
            long processing,
            long delivered,
            long discarded,
            long failed) {
    }

    public enum RetryFailedResult {
        RETRIED,
        NOT_FOUND,
        NOT_FAILED
    }

}
