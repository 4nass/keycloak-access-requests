package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        inTransaction(() -> repository.create(grant("request-4", GrantOrigin.CREATED_BY_EXTENSION)));
        entityManager.clear();

        assertTrue(inTransactionResult(() -> repository.invalidateIfVersionMatches("other-realm", "request-4", 0))
                .isEmpty());
        AccessGrant invalidated = inTransactionResult(
                () -> repository.invalidateIfVersionMatches("realm-1", "request-4", 0).orElseThrow());

        assertEquals(GrantRevocationState.INVALIDATED, invalidated.revocationState());
        assertEquals(1, invalidated.version());
        assertFalse(invalidated.canAutoRevoke());
        assertTrue(inTransactionResult(() -> repository.invalidateIfVersionMatches("realm-1", "request-4", 0)).isEmpty());
        assertTrue(inTransactionResult(() -> repository.invalidateIfVersionMatches("realm-1", "request-4", 1)).isEmpty());
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
