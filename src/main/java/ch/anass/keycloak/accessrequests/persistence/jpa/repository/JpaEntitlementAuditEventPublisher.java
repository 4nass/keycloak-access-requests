package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementAuditEventEntity;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementAuditEvent;
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
