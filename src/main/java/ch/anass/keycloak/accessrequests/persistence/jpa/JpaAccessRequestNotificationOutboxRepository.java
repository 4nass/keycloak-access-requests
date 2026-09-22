package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import jakarta.persistence.EntityManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persistence operations for the transactional notification outbox.
 */
public final class JpaAccessRequestNotificationOutboxRepository {

    private final EntityManager entityManager;

    public JpaAccessRequestNotificationOutboxRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public void enqueue(AccessRequestNotification notification, String recipientId, Instant queuedAt) {
        entityManager.persist(AccessRequestNotificationOutboxEntity.queue(notification, recipientId, queuedAt));
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
                                   entry.attemptCount = entry.attemptCount + 1
                             where entry.id = :id
                               and ((entry.state = :pending and entry.nextAttemptTimestamp <= :now)
                                 or (entry.state = :processing and entry.leaseUntilTimestamp <= :now))
                            """)
                    .setParameter("processing", AccessRequestNotificationOutboxState.PROCESSING)
                    .setParameter("processorId", processorId)
                    .setParameter("leaseUntil", leaseUntilTimestamp)
                    .setParameter("id", candidateId)
                    .setParameter("pending", AccessRequestNotificationOutboxState.PENDING)
                    .setParameter("now", nowTimestamp)
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
}
