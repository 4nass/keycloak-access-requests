package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;

import java.time.Instant;
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
    public List<AccessGrant> findDuePackageGrants(
            Instant dueAt, Instant afterExpiry, String afterRequestId, int limit) {
        Objects.requireNonNull(dueAt, "dueAt must not be null");
        if (limit < 1 || limit > 100 || (afterExpiry == null) != (afterRequestId == null)
                || (afterRequestId != null && afterRequestId.isBlank())) {
            throw new IllegalArgumentException("A bounded page and a complete cursor are required");
        }
        String cursorCondition = afterExpiry == null ? "" : """
                       and (entity.expiresTimestamp > :afterExpiry
                            or (entity.expiresTimestamp = :afterExpiry and entity.requestId > :afterRequestId))
                """;
        var query = entityManager.createQuery("""
                       select entity from AccessGrantEntity entity
                        where entity.revocationState = :authorized
                          and entity.origin = :createdByExtension
                          and entity.deliveryGroupId is not null
                          and entity.expiresTimestamp is not null
                          and entity.expiresTimestamp <= :dueAt
                """ + cursorCondition + """
                        order by entity.expiresTimestamp, entity.requestId
                """, AccessGrantEntity.class)
                .setParameter("authorized", GrantRevocationState.AUTHORIZED)
                .setParameter("createdByExtension", GrantOrigin.CREATED_BY_EXTENSION)
                .setParameter("dueAt", dueAt.toEpochMilli())
                .setMaxResults(limit);
        if (afterExpiry != null) {
            query.setParameter("afterExpiry", afterExpiry.toEpochMilli());
            query.setParameter("afterRequestId", afterRequestId);
        }
        return query.getResultList().stream().map(AccessGrantEntity::toDomain).toList();
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
        GrantRevocationState previousState;
        if (updated.revocationState() == GrantRevocationState.AUTHORIZED) {
            previousState = GrantRevocationState.UNVERIFIED;
        } else if (updated.revocationState() == GrantRevocationState.REVOKED) {
            previousState = GrantRevocationState.AUTHORIZED;
        } else {
            return Optional.empty();
        }
        if (updated.version() != expectedVersion || updated.origin() != GrantOrigin.CREATED_BY_EXTENSION
                || updated.expiresAt() == null
                || (updated.resourceType() == ResourceType.GROUP && updated.deliveryGroupId() == null)) {
            return Optional.empty();
        }

        String deliveryGroupCondition = updated.deliveryGroupId() == null
                ? " and entity.deliveryGroupId is null"
                : " and entity.deliveryGroupId = :deliveryGroupId";
        Query query = entityManager.createQuery("""
                        update AccessGrantEntity entity
                           set entity.revocationState = :nextState,
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
                           and entity.revocationState = :previousState
                        """ + deliveryGroupCondition)
                .setParameter("nextState", updated.revocationState())
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
                .setParameter("previousState", previousState);
        if (updated.deliveryGroupId() != null) {
            query.setParameter("deliveryGroupId", updated.deliveryGroupId());
        }
        int changed = query.executeUpdate();
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
