package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestHistoryReader;
import jakarta.persistence.EntityManager;

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
                         order by entity.occurredAt asc, entity.id asc
                        """, AccessRequestEventEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("requestId", requestId)
                .getResultList()
                .stream()
                .map(AccessRequestEventEntity::toDomain)
                .toList();
    }
}
