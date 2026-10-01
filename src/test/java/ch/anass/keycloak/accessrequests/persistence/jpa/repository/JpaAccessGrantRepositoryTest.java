package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestTransaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaAccessGrantRepositoryTest {

    private static EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaAccessGrantRepository repository;

    @BeforeAll
    static void startDatabase() {
        entityManagerFactory = Persistence.createEntityManagerFactory("access-requests-test");
    }

    @AfterAll
    static void stopDatabase() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void openEntityManager() {
        entityManager = entityManagerFactory.createEntityManager();
        repository = new JpaAccessGrantRepository(entityManager);
        inTransaction(
                () -> entityManager.createQuery("delete from AccessGrantEntity").executeUpdate());
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void persistsProvenanceAndScopesReadsToTheRealm() {
        AccessGrant owned = grant("request-1", GrantOrigin.CREATED_BY_EXTENSION);
        AccessGrant preexisting = grant("request-2", GrantOrigin.PREEXISTING);

        inTransaction(() -> {
            repository.create(owned);
            repository.create(preexisting);
        });
        entityManager.clear();

        assertEquals(owned, repository.findByRequestId("realm-1", "request-1").orElseThrow());
        assertEquals(preexisting, repository.findByRequestId("realm-1", "request-2").orElseThrow());
        assertTrue(repository.findByRequestId("other-realm", "request-1").isEmpty());
        assertTrue(repository.findByRequestId("realm-1", "missing").isEmpty());
        assertEquals(GrantRevocationState.UNVERIFIED,
                repository.findByRequestId("realm-1", "request-1").orElseThrow().revocationState());
        assertFalse(repository.findByRequestId("realm-1", "request-1").orElseThrow().canAutoRevoke());
        assertFalse(repository.findByRequestId("realm-1", "request-2").orElseThrow().canAutoRevoke());
    }

    @Test
    void preservesTheExpiryOfAnExtensionOwnedTemporaryGrant() {
        Instant recordedAt = Instant.parse("2026-09-01T10:15:30Z");
        AccessGrant temporary = new AccessGrant("request-temporary", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "role-1", GrantOrigin.CREATED_BY_EXTENSION,
                recordedAt, recordedAt.plus(Duration.ofHours(4)),
                GrantRevocationState.UNVERIFIED, 0);

        inTransaction(() -> repository.create(temporary));
        entityManager.clear();

        assertEquals(temporary, repository.findByRequestId("realm-1", "request-temporary").orElseThrow());
    }

    @Test
    void authorizesAndRevokesPackageMembershipWithoutLosingItsSourceOrDeliveryGroup() {
        AccessGrant jitGrant = packageGrant("request-jit", GrantRevocationState.UNVERIFIED);
        inTransaction(() -> repository.create(jitGrant));
        entityManager.clear();

        AccessGrant persisted = repository.findByRequestId("realm-1", "request-jit").orElseThrow();
        assertEquals(jitGrant, persisted);
        assertEquals("jit-group-1", persisted.deliveryGroupId());
        assertFalse(persisted.canAutoRevoke());

        AccessGrant authorized = inTransactionResult(() -> revocationRepository().updateIfVersionMatches(
                persisted.authorizeForRevocation(packageEntitlement(), true), persisted.version()).orElseThrow());
        assertEquals(GrantRevocationState.AUTHORIZED, authorized.revocationState());
        assertEquals(1, authorized.version());
        assertEquals("jit-group-1", authorized.deliveryGroupId());
        assertEquals("source-role", authorized.resourceId());
        assertEquals(jitGrant.expiresAt(), authorized.expiresAt());
        assertEquals(authorized, repository.findByRequestId("realm-1", "request-jit").orElseThrow());

        AccessGrant revoked = inTransactionResult(() -> revocationRepository().updateIfVersionMatches(
                authorized.markRevoked(), authorized.version()).orElseThrow());
        assertEquals(GrantRevocationState.REVOKED, revoked.revocationState());
        assertEquals(2, revoked.version());
        assertEquals("jit-group-1", revoked.deliveryGroupId());
        assertEquals("source-role", revoked.resourceId());
        assertEquals(jitGrant.expiresAt(), revoked.expiresAt());
        assertEquals(revoked, repository.findByRequestId("realm-1", "request-jit").orElseThrow());
        assertTrue(inTransactionResult(() -> revocationRepository().updateIfVersionMatches(
                authorized.markRevoked(), authorized.version())).isEmpty());
    }

    @Test
    void authorizesANewPackageGrantBeforeTheProvisioningTransactionCommits() {
        AccessGrant created = packageGrant("request-package-same-transaction", GrantRevocationState.UNVERIFIED);
        entityManager.getTransaction().begin();
        try {
            repository.create(created);
            AccessGrant locked = revocationRepository().findByRequestIdForUpdate(
                    created.realmId(), created.requestId()).orElseThrow();
            AccessGrant authorized = locked.authorizeForRevocation(packageEntitlement(), true);
            assertEquals(GrantRevocationState.AUTHORIZED, revocationRepository()
                    .updateIfVersionMatches(authorized, locked.version()).orElseThrow().revocationState());
            entityManager.getTransaction().commit();
        } finally {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
        }
        entityManager.clear();

        AccessGrant saved = repository.findByRequestId(created.realmId(), created.requestId()).orElseThrow();
        assertEquals(GrantRevocationState.AUTHORIZED, saved.revocationState());
        assertEquals(1, saved.version());
        assertTrue(saved.canAutoRevoke());
    }

    @Test
    void packageTransitionsRejectStaleVersionsAndForgedDeliveryGroups() {
        AccessGrant unverified = packageGrant("request-package-cas", GrantRevocationState.UNVERIFIED);
        inTransaction(() -> repository.create(unverified));
        entityManager.clear();
        AccessGrantRevocationRepository revocation = revocationRepository();
        AccessGrant authorized = unverified.authorizeForRevocation(packageEntitlement(), true);

        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(authorized, 1)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withDeliveryGroup(authorized, "other-group"), 0)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withDeliveryGroup(authorized, null), 0)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withRealm(authorized, "other-realm"), 0)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withResourceId(authorized, "other-role"), 0)).isEmpty());
        assertEquals(unverified, repository.findByRequestId("realm-1", unverified.requestId()).orElseThrow());

        AccessGrant saved = inTransactionResult(() -> revocation.updateIfVersionMatches(authorized, 0).orElseThrow());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(authorized, 0)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(saved, 1)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withDeliveryGroup(saved.markRevoked(), "other-group"), 1)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withDeliveryGroup(saved.markRevoked(), null), 1)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withResourceId(saved.markRevoked(), "other-role"), 1)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(saved.markRevoked(), 0)).isEmpty());
        assertEquals(saved, repository.findByRequestId("realm-1", unverified.requestId()).orElseThrow());
    }

    @Test
    void sourceGroupPackageAlsoRequiresTheBoundDeliveryGroupForRevocation() {
        AccessGrant unverified = new AccessGrant("request-source-group", "realm-1", "user-1", "entitlement-1",
                ResourceType.GROUP, "source-group", GrantOrigin.CREATED_BY_EXTENSION,
                Instant.parse("2026-09-01T10:15:30Z"), Instant.parse("2026-09-01T14:15:30Z"),
                GrantRevocationState.UNVERIFIED, 0, "jit-group-1");
        Entitlement entitlement = Entitlement.create("entitlement-1", "realm-1", ResourceType.GROUP,
                "source-group", "Source group", "Delivered through a JIT package", RiskLevel.LOW,
                "approver-role", Instant.parse("2026-09-01T10:00:00Z"));
        inTransaction(() -> repository.create(unverified));
        entityManager.clear();

        AccessGrant authorized = inTransactionResult(() -> revocationRepository().updateIfVersionMatches(
                unverified.authorizeForRevocation(entitlement, true), 0).orElseThrow());
        AccessGrant revoked = inTransactionResult(() -> revocationRepository().updateIfVersionMatches(
                authorized.markRevoked(), authorized.version()).orElseThrow());
        assertEquals(ResourceType.GROUP, revoked.resourceType());
        assertEquals("source-group", revoked.resourceId());
        assertEquals("jit-group-1", revoked.deliveryGroupId());
        assertEquals(2, revoked.version());
    }

    @Test
    void invalidationWinsAgainstStalePackageAuthorizationAndRevocation() {
        AccessGrant unverified = packageGrant("request-package-invalid-auth", GrantRevocationState.UNVERIFIED);
        AccessGrant authorized = packageGrant("request-package-invalid-revoke", GrantRevocationState.AUTHORIZED);
        inTransaction(() -> {
            repository.create(unverified);
            repository.create(authorized);
        });
        entityManager.clear();
        AccessGrantRevocationRepository revocation = revocationRepository();

        AccessGrant invalidUnverified = inTransactionResult(() -> repository.invalidateIfVersionMatches(
                "realm-1", unverified.requestId(), 0).orElseThrow());
        AccessGrant invalidAuthorized = inTransactionResult(() -> repository.invalidateIfVersionMatches(
                "realm-1", authorized.requestId(), 0).orElseThrow());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                unverified.authorizeForRevocation(packageEntitlement(), true), 0)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                authorized.markRevoked(), 0)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withVersion(unverified.authorizeForRevocation(packageEntitlement(), true), 1), 1)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withVersion(authorized.markRevoked(), 1), 1)).isEmpty());
        assertEquals("jit-group-1", invalidUnverified.deliveryGroupId());
        assertEquals("jit-group-1", invalidAuthorized.deliveryGroupId());
        assertEquals(GrantRevocationState.INVALIDATED,
                repository.findByRequestId("realm-1", unverified.requestId()).orElseThrow().revocationState());
        assertEquals(GrantRevocationState.INVALIDATED,
                repository.findByRequestId("realm-1", authorized.requestId()).orElseThrow().revocationState());
    }

    @Test
    void packageRevocationRequiresPriorAuthorizationAndAnExtensionOwnedTemporaryGrant() {
        AccessGrant unverified = packageGrant("request-package-unverified", GrantRevocationState.UNVERIFIED);
        AccessGrant preexisting = new AccessGrant("request-package-preexisting", "realm-1", "user-1",
                "entitlement-1", ResourceType.REALM_ROLE, "source-role", GrantOrigin.PREEXISTING,
                unverified.recordedAt(), null, GrantRevocationState.UNVERIFIED, 0, "jit-group-1");
        AccessGrant permanent = new AccessGrant("request-package-permanent", "realm-1", "user-1",
                "entitlement-1", ResourceType.REALM_ROLE, "source-role", GrantOrigin.CREATED_BY_EXTENSION,
                unverified.recordedAt(), null, GrantRevocationState.UNVERIFIED, 0, "jit-group-1");
        inTransaction(() -> {
            repository.create(unverified);
            repository.create(preexisting);
            repository.create(permanent);
        });
        entityManager.clear();
        AccessGrantRevocationRepository revocation = revocationRepository();

        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withState(unverified, GrantRevocationState.REVOKED), 0)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withState(preexisting, GrantRevocationState.AUTHORIZED, GrantOrigin.CREATED_BY_EXTENSION), 0))
                .isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                withState(permanent, GrantRevocationState.AUTHORIZED), 0)).isEmpty());
        assertEquals(unverified, repository.findByRequestId("realm-1", unverified.requestId()).orElseThrow());
        assertEquals(preexisting, repository.findByRequestId("realm-1", preexisting.requestId()).orElseThrow());
        assertEquals(permanent, repository.findByRequestId("realm-1", permanent.requestId()).orElseThrow());
    }

    @Test
    void rolledBackPackageAuthorizationAndRevocationRemainRetryable() {
        AccessGrant unverified = packageGrant("request-package-rollback", GrantRevocationState.UNVERIFIED);
        inTransaction(() -> repository.create(unverified));
        entityManager.clear();
        AccessGrantRevocationRepository revocation = revocationRepository();

        entityManager.getTransaction().begin();
        try {
            AccessGrant locked = revocation.findByRequestIdForUpdate("realm-1", unverified.requestId()).orElseThrow();
            assertEquals(GrantRevocationState.AUTHORIZED, revocation.updateIfVersionMatches(
                    locked.authorizeForRevocation(packageEntitlement(), true), 0).orElseThrow().revocationState());
        } finally {
            entityManager.getTransaction().rollback();
            entityManager.clear();
        }
        assertEquals(unverified, repository.findByRequestId("realm-1", unverified.requestId()).orElseThrow());

        AccessGrant authorized = inTransactionResult(() -> revocation.updateIfVersionMatches(
                unverified.authorizeForRevocation(packageEntitlement(), true), 0).orElseThrow());
        entityManager.getTransaction().begin();
        try {
            AccessGrant locked = revocation.findByRequestIdForUpdate("realm-1", authorized.requestId()).orElseThrow();
            assertEquals(GrantRevocationState.REVOKED, revocation.updateIfVersionMatches(
                    locked.markRevoked(), locked.version()).orElseThrow().revocationState());
        } finally {
            entityManager.getTransaction().rollback();
            entityManager.clear();
        }
        assertEquals(authorized, repository.findByRequestId("realm-1", authorized.requestId()).orElseThrow());
        assertEquals(GrantRevocationState.REVOKED, inTransactionResult(() -> revocation.updateIfVersionMatches(
                authorized.markRevoked(), authorized.version()).orElseThrow()).revocationState());
    }

    @Test
    void doesNotPersistAGrantWhenItsTransactionRollsBack() {
        AccessGrant grant = grant("request-3", GrantOrigin.CREATED_BY_EXTENSION);
        entityManager.getTransaction().begin();
        repository.create(grant);
        entityManager.flush();
        entityManager.getTransaction().rollback();
        entityManager.clear();

        assertTrue(repository.findByRequestId("realm-1", "request-3").isEmpty());
    }

    @Test
    void invalidatesAGrantOnceWithOptimisticLockingAndRealmIsolation() {
        Instant recordedAt = Instant.parse("2026-09-01T10:15:30Z");
        AccessGrant expiring = new AccessGrant("request-4", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "role-1", GrantOrigin.CREATED_BY_EXTENSION,
                recordedAt, recordedAt.plus(Duration.ofHours(4)), GrantRevocationState.UNVERIFIED, 0);
        inTransaction(() -> repository.create(expiring));
        entityManager.clear();

        assertTrue(inTransactionResult(() -> repository.invalidateIfVersionMatches("other-realm", "request-4", 0))
                .isEmpty());
        AccessGrant invalidated = inTransactionResult(
                () -> repository.invalidateIfVersionMatches("realm-1", "request-4", 0).orElseThrow());

        assertEquals(GrantRevocationState.INVALIDATED, invalidated.revocationState());
        assertEquals(expiring.expiresAt(), invalidated.expiresAt());
        assertEquals(1, invalidated.version());
        assertFalse(invalidated.canAutoRevoke());
        assertTrue(inTransactionResult(() -> repository.invalidateIfVersionMatches("realm-1", "request-4", 0)).isEmpty());
        assertTrue(inTransactionResult(() -> repository.invalidateIfVersionMatches("realm-1", "request-4", 1)).isEmpty());
    }

    @Test
    void revocationUpdateRequiresTheExpectedRealmVersionAndAuthorizedState() {
        AccessGrant authorized = authorizedGrant("request-revoke");
        inTransaction(() -> repository.create(authorized));
        entityManager.clear();
        AccessGrantRevocationRepository revocation = revocationRepository();
        AccessGrant revoked = authorized.markRevoked();

        assertTrue(inTransactionResult(() -> revocation.findByRequestIdForUpdate(
                "other-realm", authorized.requestId())).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(revoked, 1)).isEmpty());
        AccessGrant wrongRealm = new AccessGrant(revoked.requestId(), "other-realm", revoked.requesterId(),
                revoked.entitlementId(), revoked.resourceType(), revoked.resourceId(), revoked.origin(),
                revoked.recordedAt(), revoked.expiresAt(), revoked.revocationState(), revoked.version());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(wrongRealm, 0)).isEmpty());
        AccessGrant changedResource = new AccessGrant(revoked.requestId(), revoked.realmId(), revoked.requesterId(),
                revoked.entitlementId(), revoked.resourceType(), "other-role", revoked.origin(),
                revoked.recordedAt(), revoked.expiresAt(), revoked.revocationState(), revoked.version());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(changedResource, 0)).isEmpty());
        AccessGrant forgedVersion = new AccessGrant(revoked.requestId(), revoked.realmId(), revoked.requesterId(),
                revoked.entitlementId(), revoked.resourceType(), revoked.resourceId(), revoked.origin(),
                revoked.recordedAt(), revoked.expiresAt(), revoked.revocationState(), 9);
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(forgedVersion, 0)).isEmpty());
        assertEquals(authorized, repository.findByRequestId("realm-1", authorized.requestId()).orElseThrow());

        AccessGrant persisted = inTransactionResult(() -> revocation.updateIfVersionMatches(revoked, 0).orElseThrow());
        assertEquals(GrantRevocationState.REVOKED, persisted.revocationState());
        assertEquals(1, persisted.version());
        assertEquals(authorized.expiresAt(), persisted.expiresAt());
        assertEquals(authorized.resourceId(), persisted.resourceId());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(revoked, 0)).isEmpty());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(revoked, 1)).isEmpty());
        assertTrue(inTransactionResult(() -> repository.invalidateIfVersionMatches(
                "realm-1", authorized.requestId(), 1)).isEmpty());
    }

    @Test
    void invalidationPreventsAStaleRevocationAndUnauthorizedGrantsCannotBeMarkedRevoked() {
        AccessGrant authorized = authorizedGrant("request-invalidated");
        AccessGrant unverified = temporaryGrant("request-unverified", GrantRevocationState.UNVERIFIED);
        inTransaction(() -> {
            repository.create(authorized);
            repository.create(unverified);
        });
        entityManager.clear();
        AccessGrantRevocationRepository revocation = revocationRepository();

        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                asRevoked(unverified), 0)).isEmpty());
        assertEquals(GrantRevocationState.INVALIDATED, inTransactionResult(() -> repository
                .invalidateIfVersionMatches("realm-1", authorized.requestId(), 0).orElseThrow())
                .revocationState());
        assertTrue(inTransactionResult(() -> revocation.updateIfVersionMatches(
                authorized.markRevoked(), 0)).isEmpty());
        assertEquals(GrantRevocationState.INVALIDATED,
                repository.findByRequestId("realm-1", authorized.requestId()).orElseThrow().revocationState());
    }

    @Test
    void aRolledBackRevocationLeavesTheGrantAuthorizedForRetry() {
        AccessGrant authorized = authorizedGrant("request-rollback");
        inTransaction(() -> repository.create(authorized));
        entityManager.clear();
        AccessGrantRevocationRepository revocation = revocationRepository();

        entityManager.getTransaction().begin();
        try {
            revocation.findByRequestIdForUpdate("realm-1", authorized.requestId()).orElseThrow();
            assertEquals(GrantRevocationState.REVOKED,
                    revocation.updateIfVersionMatches(authorized.markRevoked(), 0).orElseThrow().revocationState());
        } finally {
            entityManager.getTransaction().rollback();
            entityManager.clear();
        }

        assertEquals(authorized, repository.findByRequestId("realm-1", authorized.requestId()).orElseThrow());
    }

    @Test
    void pessimisticGrantLockSerializesTwoTransactions() throws Exception {
        revocationRepository();
        AccessGrant authorized = authorizedGrant("request-lock");
        inTransaction(() -> repository.create(authorized));
        entityManager.clear();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> {
                EntityManager manager = entityManagerFactory.createEntityManager();
                try {
                    new JpaAccessRequestTransaction(manager).execute(() -> {
                        AccessGrantRevocationRepository revocation = AccessGrantRevocationRepository.class.cast(
                                new JpaAccessGrantRepository(manager));
                        AccessGrant locked = revocation.findByRequestIdForUpdate("realm-1", authorized.requestId())
                                .orElseThrow();
                        firstLocked.countDown();
                        await(releaseFirst);
                        revocation.updateIfVersionMatches(locked.markRevoked(), locked.version()).orElseThrow();
                        return null;
                    });
                } finally {
                    manager.close();
                }
            });
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));

            Future<Optional<AccessGrant>> second = workers.submit(() -> {
                EntityManager manager = entityManagerFactory.createEntityManager();
                try {
                    secondAttempted.countDown();
                    return new JpaAccessRequestTransaction(manager).execute(() ->
                            AccessGrantRevocationRepository.class.cast(new JpaAccessGrantRepository(manager))
                                    .findByRequestIdForUpdate("realm-1", authorized.requestId()));
                } finally {
                    manager.close();
                }
            });
            assertTrue(secondAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertEquals(GrantRevocationState.REVOKED, second.get(10, TimeUnit.SECONDS).orElseThrow().revocationState());
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    private AccessGrantRevocationRepository revocationRepository() {
        return assertInstanceOf(AccessGrantRevocationRepository.class, repository);
    }

    private static AccessGrant authorizedGrant(String requestId) {
        return temporaryGrant(requestId, GrantRevocationState.AUTHORIZED);
    }

    private static AccessGrant packageGrant(String requestId, GrantRevocationState state) {
        Instant recordedAt = Instant.parse("2026-09-01T10:15:30Z");
        return new AccessGrant(requestId, "realm-1", "user-1", "entitlement-1", ResourceType.REALM_ROLE,
                "source-role", GrantOrigin.CREATED_BY_EXTENSION, recordedAt,
                recordedAt.plus(Duration.ofHours(4)), state, 0, "jit-group-1");
    }

    private static Entitlement packageEntitlement() {
        return Entitlement.create("entitlement-1", "realm-1", ResourceType.REALM_ROLE, "source-role",
                "Source role", "Delivered through a JIT package", RiskLevel.LOW, "approver-role",
                Instant.parse("2026-09-01T10:00:00Z"));
    }

    private static AccessGrant withDeliveryGroup(AccessGrant grant, String deliveryGroupId) {
        return new AccessGrant(grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), grant.origin(), grant.recordedAt(), grant.expiresAt(),
                grant.revocationState(), grant.version(), deliveryGroupId);
    }

    private static AccessGrant withRealm(AccessGrant grant, String realmId) {
        return new AccessGrant(grant.requestId(), realmId, grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), grant.origin(), grant.recordedAt(), grant.expiresAt(),
                grant.revocationState(), grant.version(), grant.deliveryGroupId());
    }

    private static AccessGrant withResourceId(AccessGrant grant, String resourceId) {
        return new AccessGrant(grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), resourceId, grant.origin(), grant.recordedAt(), grant.expiresAt(),
                grant.revocationState(), grant.version(), grant.deliveryGroupId());
    }

    private static AccessGrant withState(AccessGrant grant, GrantRevocationState state) {
        return withState(grant, state, grant.origin());
    }

    private static AccessGrant withState(AccessGrant grant, GrantRevocationState state, GrantOrigin origin) {
        return new AccessGrant(grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), origin, grant.recordedAt(), grant.expiresAt(),
                state, grant.version(), grant.deliveryGroupId());
    }

    private static AccessGrant withVersion(AccessGrant grant, long version) {
        return new AccessGrant(grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), grant.origin(), grant.recordedAt(), grant.expiresAt(),
                grant.revocationState(), version, grant.deliveryGroupId());
    }

    private static AccessGrant temporaryGrant(String requestId, GrantRevocationState state) {
        Instant recordedAt = Instant.parse("2026-09-01T10:15:30Z");
        return new AccessGrant(requestId, "realm-1", "user-1", "entitlement-1", ResourceType.REALM_ROLE,
                "jit-role-1", GrantOrigin.CREATED_BY_EXTENSION, recordedAt, recordedAt.plus(Duration.ofHours(4)),
                state, 0);
    }

    private static AccessGrant asRevoked(AccessGrant grant) {
        return new AccessGrant(grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), grant.origin(), grant.recordedAt(), grant.expiresAt(),
                GrantRevocationState.REVOKED, grant.version());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static AccessGrant grant(String requestId, GrantOrigin origin) {
        return new AccessGrant(requestId, "realm-1", "user-1", "entitlement-1", ResourceType.REALM_ROLE,
                "role-1", origin, Instant.parse("2026-09-01T10:15:30Z"));
    }

    private void inTransaction(Runnable operation) {
        entityManager.getTransaction().begin();
        try {
            operation.run();
            entityManager.getTransaction().commit();
        } catch (RuntimeException exception) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw exception;
        }
    }

    private <T> T inTransactionResult(java.util.function.Supplier<T> operation) {
        entityManager.getTransaction().begin();
        try {
            T result = operation.get();
            entityManager.getTransaction().commit();
            return result;
        } catch (RuntimeException exception) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw exception;
        }
    }
}
