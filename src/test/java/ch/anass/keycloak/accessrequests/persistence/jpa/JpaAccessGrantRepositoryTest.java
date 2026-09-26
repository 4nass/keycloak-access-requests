package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
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
        assertTrue(repository.findByRequestId("realm-1", "request-1").orElseThrow().ownedByExtension());
        assertFalse(repository.findByRequestId("realm-1", "request-2").orElseThrow().ownedByExtension());
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
}
