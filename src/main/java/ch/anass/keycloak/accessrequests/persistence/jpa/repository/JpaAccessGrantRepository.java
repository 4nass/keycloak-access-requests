package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JpaAccessGrantRepository implements AccessGrantRevocationRepository {

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
    public Optional<AccessGrant> findByRequestIdForUpdate(String realmId, String requestId) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        return entityManager.createQuery("""
                        select entity from AccessGrantEntity entity
                         where entity.requestId = :requestId and entity.realmId = :realmId
                        """, AccessGrantEntity.class)
                .setParameter("requestId", requestId)
                .setParameter("realmId", realmId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .map(entity -> {
                    entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
                    return entity.toDomain();
                });
    }

    @Override
    public Optional<AccessGrant> updateIfVersionMatches(AccessGrant updated, long expectedVersion) {
        Objects.requireNonNull(updated, "updated must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (updated.version() != expectedVersion || updated.revocationState() != GrantRevocationState.REVOKED
                || updated.origin() != GrantOrigin.CREATED_BY_EXTENSION || updated.expiresAt() == null) {
            return Optional.empty();
        }

        int changed = entityManager.createQuery("""
                        update AccessGrantEntity entity
                           set entity.revocationState = :revoked,
                               entity.version = entity.version + 1
                         where entity.requestId = :requestId
                           and entity.realmId = :realmId
                           and entity.requesterId = :requesterId
                           and entity.entitlementId = :entitlementId
                           and entity.resourceType = :resourceType
                           and entity.resourceId = :resourceId
                           and entity.origin = :origin
                           and entity.recordedTimestamp = :recordedTimestamp
                           and entity.expiresTimestamp = :expiresTimestamp
                           and entity.version = :expectedVersion
                           and entity.revocationState = :authorized
                        """)
                .setParameter("revoked", GrantRevocationState.REVOKED)
                .setParameter("requestId", updated.requestId())
                .setParameter("realmId", updated.realmId())
                .setParameter("requesterId", updated.requesterId())
                .setParameter("entitlementId", updated.entitlementId())
                .setParameter("resourceType", updated.resourceType())
                .setParameter("resourceId", updated.resourceId())
                .setParameter("origin", updated.origin())
                .setParameter("recordedTimestamp", updated.recordedAt().toEpochMilli())
                .setParameter("expiresTimestamp", updated.expiresAt().toEpochMilli())
                .setParameter("expectedVersion", expectedVersion)
                .setParameter("authorized", GrantRevocationState.AUTHORIZED)
                .executeUpdate();
        if (changed == 0) {
            return Optional.empty();
        }
        AccessGrantEntity entity = entityManager.find(AccessGrantEntity.class, updated.requestId());
        entityManager.refresh(entity);
        return Optional.of(entity.toDomain());
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
