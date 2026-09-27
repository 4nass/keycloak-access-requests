package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestEventEntity;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestEventPublisher;
import jakarta.persistence.EntityManager;

import java.util.Objects;

public final class JpaAccessRequestEventPublisher implements AccessRequestEventPublisher {

    private final EntityManager entityManager;

    public JpaAccessRequestEventPublisher(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    public void publish(AccessRequestEvent event) {
        entityManager.persist(new AccessRequestEventEntity(event));
    }
}
