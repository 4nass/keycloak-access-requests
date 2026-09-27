package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRepository;
import jakarta.persistence.EntityManager;

import java.util.List;
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

    @Override
    public Optional<AccessGrant> invalidateIfVersionMatches(String realmId, String requestId, long expectedVersion) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        int updated = entityManager.createQuery("""
                        update AccessGrantEntity entity
                           set entity.revocationState = :invalidated,
                               entity.version = entity.version + 1
                         where entity.requestId = :requestId
                           and entity.realmId = :realmId
                           and entity.version = :expectedVersion
                           and entity.revocationState in :activeStates
                        """)
                .setParameter("invalidated", GrantRevocationState.INVALIDATED)
                .setParameter("activeStates", List.of(
                        GrantRevocationState.UNVERIFIED, GrantRevocationState.AUTHORIZED))
                .setParameter("requestId", requestId)
                .setParameter("realmId", realmId)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        if (updated == 0) {
            return Optional.empty();
        }
        entityManager.flush();
        entityManager.clear();
        return findByRequestId(realmId, requestId);
    }
}
