package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
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
    void persistsPackageDeliveryGroupWhileJpaRevocationRemainsUnsupported() {
        Instant recordedAt = Instant.parse("2026-09-01T10:15:30Z");
        AccessGrant jitGrant = new AccessGrant("request-jit", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "source-role", GrantOrigin.CREATED_BY_EXTENSION,
                recordedAt, recordedAt.plus(Duration.ofHours(4)), GrantRevocationState.AUTHORIZED, 0,
                "jit-group-1");
        inTransaction(() -> repository.create(jitGrant));
        entityManager.clear();

        AccessGrant persisted = repository.findByRequestId("realm-1", "request-jit").orElseThrow();
        assertEquals(jitGrant, persisted);
        assertEquals("jit-group-1", persisted.deliveryGroupId());
        assertTrue(persisted.canAutoRevoke(), "Core eligibility still requires a fresh authority check before removal");
        assertTrue(inTransactionResult(() -> revocationRepository().updateIfVersionMatches(
                new AccessGrant(persisted.requestId(), persisted.realmId(), persisted.requesterId(),
                        persisted.entitlementId(), persisted.resourceType(), persisted.resourceId(),
                        persisted.origin(), persisted.recordedAt(), persisted.expiresAt(),
                        GrantRevocationState.REVOKED, persisted.version(), persisted.deliveryGroupId()), 0)).isEmpty());
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
