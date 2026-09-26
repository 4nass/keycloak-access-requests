package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessGrant;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRepository;
import jakarta.persistence.EntityManager;

import java.util.Objects;
import java.util.Optional;

public final class JpaAccessGrantRepository implements AccessGrantRepository {

    private final EntityManager entityManager;

    public JpaAccessGrantRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    public void create(AccessGrant grant) {
        entityManager.persist(AccessGrantEntity.from(Objects.requireNonNull(grant, "grant must not be null")));
    }

    @Override
    public Optional<AccessGrant> findByRequestId(String realmId, String requestId) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        AccessGrantEntity entity = entityManager.find(AccessGrantEntity.class, requestId);
        return entity != null && realmId.equals(entity.realmId())
                ? Optional.of(entity.toDomain())
                : Optional.empty();
    }
}
