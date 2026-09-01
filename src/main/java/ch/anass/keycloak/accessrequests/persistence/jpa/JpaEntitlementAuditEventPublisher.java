package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.EntitlementAuditEvent;
import ch.anass.keycloak.accessrequests.core.port.EntitlementAuditEventPublisher;
import jakarta.persistence.EntityManager;

import java.util.Objects;

public final class JpaEntitlementAuditEventPublisher implements EntitlementAuditEventPublisher {

    private final EntityManager entityManager;

    public JpaEntitlementAuditEventPublisher(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    public void publish(EntitlementAuditEvent event) {
        entityManager.persist(new EntitlementAuditEventEntity(event));
    }
}
