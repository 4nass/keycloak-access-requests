package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestHistoryReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JpaAccessRequestHistoryReader implements AccessRequestHistoryReader {

    private final EntityManager entityManager;

    public JpaAccessRequestHistoryReader(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    public List<AccessRequestEvent> findByRequestId(String realmId, String requestId) {
        return entityManager.createQuery("""
                        select entity
                          from AccessRequestEventEntity entity
                         where entity.realmId = :realmId
                           and entity.requestId = :requestId
                        """, AccessRequestEventEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("requestId", requestId)
                .getResultList()
                .stream()
                .map(AccessRequestEventEntity::toDomain)
                // Millisecond timestamps can tie. Versions order state changes; phases order events
                // sharing a version (including a failed attempt followed by the next retry).
                // Legacy events have no retry cycle, so their initial start precedes its result.
                .sorted(Comparator.comparing(AccessRequestEvent::occurredAt)
                        .thenComparing(AccessRequestEvent::requestVersion,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(JpaAccessRequestHistoryReader::phaseOrder)
                        .thenComparing(AccessRequestEvent::id))
                .toList();
    }

    public AuditEventPage findPageByRequestId(String realmId, String requestId, int page, int size) {
        requirePage(page, size);
        if (realmId == null || realmId.isBlank() || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("realmId and requestId must be provided");
        }
        String conditions = " where entity.realmId = :realmId and entity.requestId = :requestId";
        long total = entityManager.createQuery(
                        "select count(entity) from AccessRequestEventEntity entity" + conditions, Long.class)
                .setParameter("realmId", realmId)
                .setParameter("requestId", requestId)
                .getSingleResult();
        TypedQuery<AccessRequestEventEntity> query = entityManager.createQuery("""
                        select entity from AccessRequestEventEntity entity
                        """ + conditions + "\n" + """
                        order by entity.occurredAt asc,
                                 coalesce(entity.requestVersion, -1) asc,
                                 case
                                     when entity.type = :closed then 4
                                     when entity.type = :started and entity.requestVersion is not null then 3
                                     when entity.type in (:success, :failure) and entity.requestVersion is null then 3
                                     when entity.type in (:success, :failure, :started) then 2
                                     when entity.type in (:approved, :rejected, :canceled) then 1
                                     else 0
                                 end asc,
                                 entity.id asc
                        """, AccessRequestEventEntity.class);
        query.setParameter("realmId", realmId);
        query.setParameter("requestId", requestId);
        setPhaseParameters(query);
        List<AccessRequestEvent> items = query.setFirstResult(page * size).setMaxResults(size)
                .getResultList().stream().map(AccessRequestEventEntity::toDomain).toList();
        return new AuditEventPage(items, page, size, total);
    }

    public AuditEventPage findAll(String realmId, Instant from, Instant to, AccessRequestEventType type,
            String actorId, String requestId, int page, int size) {
        if (realmId == null || realmId.isBlank()) {
            throw new IllegalArgumentException("realmId must be provided");
        }
        requirePage(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }

        StringBuilder conditions = new StringBuilder(" where entity.realmId = :realmId");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("realmId", realmId);
        if (from != null) {
            conditions.append(" and entity.occurredAt >= :from");
            parameters.put("from", inclusiveLowerBoundInMillis(from));
        }
        if (to != null) {
            conditions.append(" and entity.occurredAt <= :to");
            parameters.put("to", epochMillis(to, "to"));
        }
        if (type != null) {
            conditions.append(" and entity.type = :type");
            parameters.put("type", type);
        }
        if (actorId != null && !actorId.isBlank()) {
            conditions.append(" and entity.actorId = :actorId");
            parameters.put("actorId", actorId);
        }
        if (requestId != null && !requestId.isBlank()) {
            conditions.append(" and entity.requestId = :requestId");
            parameters.put("requestId", requestId);
        }

        TypedQuery<Long> count = entityManager.createQuery(
                "select count(entity) from AccessRequestEventEntity entity" + conditions, Long.class);
        parameters.forEach(count::setParameter);
        long total = count.getSingleResult();

        TypedQuery<AccessRequestEventEntity> query = entityManager.createQuery("""
                        select entity from AccessRequestEventEntity entity
                        """ + conditions + "\n" + """
                        order by entity.occurredAt desc,
                                 coalesce(entity.requestVersion, -1) desc,
                                 case
                                     when entity.type = :closed then 4
                                     when entity.type = :started and entity.requestVersion is not null then 3
                                     when entity.type in (:success, :failure) and entity.requestVersion is null then 3
                                     when entity.type in (:success, :failure, :started) then 2
                                     when entity.type in (:approved, :rejected, :canceled) then 1
                                     else 0
                                 end desc,
                                 entity.id desc
                        """, AccessRequestEventEntity.class);
        parameters.forEach(query::setParameter);
        setPhaseParameters(query);
        List<AccessRequestEvent> items = query.setFirstResult(page * size)
                .setMaxResults(size).getResultList().stream().map(AccessRequestEventEntity::toDomain).toList();
        return new AuditEventPage(items, page, size, total);
    }

    private static void setPhaseParameters(TypedQuery<AccessRequestEventEntity> query) {
        query.setParameter("closed", AccessRequestEventType.PROVISIONING_CLOSED);
        query.setParameter("started", AccessRequestEventType.PROVISIONING_STARTED);
        query.setParameter("success", AccessRequestEventType.PROVISIONING_SUCCEEDED);
        query.setParameter("failure", AccessRequestEventType.PROVISIONING_FAILED);
        query.setParameter("approved", AccessRequestEventType.REQUEST_APPROVED);
        query.setParameter("rejected", AccessRequestEventType.REQUEST_REJECTED);
        query.setParameter("canceled", AccessRequestEventType.REQUEST_CANCELED);
    }

    private static void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page and size must define a bounded audit query");
        }
    }

    public record AuditEventPage(List<AccessRequestEvent> items, int page, int size, long total) {
        public AuditEventPage {
            items = List.copyOf(items);
        }
    }

    private static long inclusiveLowerBoundInMillis(Instant instant) {
        long wholeMillisecond = epochMillis(instant, "from");
        // Event timestamps are stored in milliseconds. A later nanosecond within the same
        // millisecond must exclude the event at the truncated lower bound.
        if (instant.getNano() % 1_000_000 == 0) {
            return wholeMillisecond;
        }
        try {
            return Math.addExact(wholeMillisecond, 1);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("from is outside the supported timestamp range", exception);
        }
    }

    private static long epochMillis(Instant instant, String parameter) {
        try {
            return instant.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(parameter + " is outside the supported timestamp range", exception);
        }
    }

    private static int phaseOrder(AccessRequestEvent event) {
        AccessRequestEventType type = event.type();
        return switch (type) {
            case REQUEST_CREATED -> 0;
            case REQUEST_APPROVED, REQUEST_REJECTED, REQUEST_CANCELED -> 1;
            case PROVISIONING_SUCCEEDED, PROVISIONING_FAILED -> event.requestVersion() == null ? 3 : 2;
            case PROVISIONING_STARTED -> event.requestVersion() == null ? 2 : 3;
            case PROVISIONING_CLOSED -> 4;
        };
    }
}
