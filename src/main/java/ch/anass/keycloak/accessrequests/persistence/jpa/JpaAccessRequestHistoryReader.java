package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestHistoryReader;
import jakarta.persistence.EntityManager;

import java.util.Comparator;
import java.util.List;
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
