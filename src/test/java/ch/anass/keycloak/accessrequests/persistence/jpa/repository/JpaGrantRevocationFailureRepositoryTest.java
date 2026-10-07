package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
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

class JpaGrantRevocationFailureRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static EntityManagerFactory factory;
    private EntityManager entityManager;
    private JpaAccessGrantRepository grants;
    private JpaGrantRevocationFailureRepository failures;

    @BeforeAll
    static void startDatabase() {
        factory = Persistence.createEntityManagerFactory("access-requests-test");
    }

    @AfterAll
    static void stopDatabase() {
        factory.close();
    }

    @BeforeEach
    void openEntityManager() {
        entityManager = factory.createEntityManager();
        grants = new JpaAccessGrantRepository(entityManager);
        failures = new JpaGrantRevocationFailureRepository(entityManager);
        inTransaction(() -> {
            entityManager.createQuery("delete from GrantRevocationFailureEntity").executeUpdate();
            entityManager.createQuery("delete from AccessGrantEntity").executeUpdate();
            grants.create(grant("request-1", "realm-1", GrantRevocationState.AUTHORIZED));
            grants.create(grant("request-2", "realm-2", GrantRevocationState.AUTHORIZED));
        });
        entityManager.clear();
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void recordsAfterRollbackAndBacksOffWithoutDroppingTheAuthorizedGrant() {
        inTransaction(() -> failures.record("realm-1", "request-1",
                GrantRevocationFailureCode.REMOVAL_FAILED, NOW));
        entityManager.clear();

        var failure = failures.findOpen("realm-1", "request-1").orElseThrow();
        assertEquals(1, failure.attemptCount());
        assertEquals(NOW.plusSeconds(300), failure.nextAttemptAt());
        assertEquals(GrantRevocationState.AUTHORIZED,
                grants.findByRequestId("realm-1", "request-1").orElseThrow().revocationState());
        assertTrue(grants.findDuePackageGrants(NOW, null, null, 10).stream()
                .noneMatch(grant -> grant.requestId().equals("request-1")));
        assertTrue(grants.findRetryableFailedPackageGrants(NOW, 10).isEmpty());
        assertTrue(grants.findDuePackageGrants(NOW.plusSeconds(300), null, null, 10).stream()
                .noneMatch(grant -> grant.requestId().equals("request-1")));
        assertTrue(grants.findRetryableFailedPackageGrants(NOW.plusSeconds(300), 10).stream()
                .anyMatch(grant -> grant.requestId().equals("request-1")));

        inTransaction(() -> failures.record("realm-1", "request-1",
                GrantRevocationFailureCode.AUTHORITY_UNVERIFIABLE, NOW.plusSeconds(300)));
        entityManager.clear();
        failure = failures.findOpen("realm-1", "request-1").orElseThrow();
        assertEquals(2, failure.attemptCount());
        assertEquals(GrantRevocationFailureCode.AUTHORITY_UNVERIFIABLE, failure.code());
        assertEquals(NOW.plusSeconds(900), failure.nextAttemptAt());
        assertEquals(1, failures.findPage("realm-1", true, 0, 20).total());
        assertEquals(0, failures.findPage("realm-2", true, 0, 20).total());
    }

    @Test
    void successfulRemovalResolvesTheFailureAndKeepsItsArchive() {
        inTransaction(() -> failures.record("realm-1", "request-1",
                GrantRevocationFailureCode.REMOVAL_FAILED, NOW));
        entityManager.clear();
        inTransaction(() -> {
            var grant = grants.findByRequestIdForUpdate("realm-1", "request-1").orElseThrow();
            grants.updateIfVersionMatches(grant.markRevoked(), grant.version()).orElseThrow();
            failures.resolve("realm-1", "request-1", NOW.plusSeconds(60));
        });
        entityManager.clear();

        assertTrue(failures.findOpen("realm-1", "request-1").isEmpty());
        assertEquals(0, failures.findPage("realm-1", true, 0, 20).total());
        assertEquals(NOW.plusSeconds(60),
                failures.findPage("realm-1", false, 0, 20).items().getFirst().failure().resolvedAt());
        assertFalse(grants.findDuePackageGrants(NOW.plusSeconds(3600), null, null, 10).stream()
                .anyMatch(grant -> grant.requestId().equals("request-1")));
    }

    @Test
    void neverRecordsAnotherRealmsGrantOrACompletedRevocation() {
        inTransaction(() -> {
            assertTrue(failures.record("realm-2", "request-1",
                    GrantRevocationFailureCode.UNEXPECTED_FAILURE, NOW).isEmpty());
            var grant = grants.findByRequestIdForUpdate("realm-1", "request-1").orElseThrow();
            grants.updateIfVersionMatches(grant.markRevoked(), grant.version()).orElseThrow();
            assertTrue(failures.record("realm-1", "request-1",
                    GrantRevocationFailureCode.UNEXPECTED_FAILURE, NOW).isEmpty());
        });
        assertEquals(0, failures.findPage("realm-1", true, 0, 20).total());
    }

    private static AccessGrant grant(String requestId, String realmId, GrantRevocationState state) {
        return new AccessGrant(requestId, realmId, "user-1", "entitlement-1", ResourceType.REALM_ROLE,
                "source-role", GrantOrigin.CREATED_BY_EXTENSION, NOW.minusSeconds(3600),
                NOW.minusSeconds(1), state, 0, "package-group");
    }

    private void inTransaction(Runnable operation) {
        entityManager.getTransaction().begin();
        try {
            operation.run();
            entityManager.getTransaction().commit();
        } catch (RuntimeException exception) {
            entityManager.getTransaction().rollback();
            throw exception;
        }
    }
}
