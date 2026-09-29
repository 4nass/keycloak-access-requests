package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestTransaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the actual database lock across two PostgreSQL connections, not only an in-memory test double. */
@Testcontainers(disabledWithoutDocker = true)
class JpaAccessGrantRevocationPostgresIT {

    private static final String DEFAULT_POSTGRESQL_CONTAINER = "mirror.gcr.io/postgres:18";
    private static final DockerImageName POSTGRESQL_IMAGE = DockerImageName.parse(System.getProperty(
            "postgresql.container", DEFAULT_POSTGRESQL_CONTAINER)).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRESQL_IMAGE)
            .withDatabaseName("access_requests_revocation")
            .withUsername("access_requests")
            .withPassword("access_requests");

    private static EntityManagerFactory factory;

    @BeforeAll
    static void openDatabase() {
        factory = Persistence.createEntityManagerFactory("access-requests-test", Map.of(
                "jakarta.persistence.jdbc.driver", "org.postgresql.Driver",
                "jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl(),
                "jakarta.persistence.jdbc.user", POSTGRES.getUsername(),
                "jakarta.persistence.jdbc.password", POSTGRES.getPassword(),
                "hibernate.hbm2ddl.auto", "create-drop"));
    }

    @AfterAll
    static void closeDatabase() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void aSecondNodeWaitsForTheFirstRevocationAndObservesItsCommittedState() throws Exception {
        Instant activatedAt = Instant.parse("2026-09-01T10:00:00Z");
        AccessGrant authorized = new AccessGrant("request-postgres-lock", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "jit-role-1", GrantOrigin.CREATED_BY_EXTENSION,
                activatedAt, activatedAt.plus(Duration.ofHours(4)), GrantRevocationState.AUTHORIZED, 0);
        EntityManager setup = factory.createEntityManager();
        try {
            revocationRepository(setup);
            new JpaAccessRequestTransaction(setup).execute(() -> {
                new JpaAccessGrantRepository(setup).create(authorized);
                return null;
            });
        } finally {
            setup.close();
        }

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> {
                EntityManager manager = factory.createEntityManager();
                try {
                    new JpaAccessRequestTransaction(manager).execute(() -> {
                        AccessGrantRevocationRepository repository = revocationRepository(manager);
                        AccessGrant locked = repository.findByRequestIdForUpdate("realm-1", authorized.requestId())
                                .orElseThrow();
                        firstLocked.countDown();
                        await(releaseFirst);
                        repository.updateIfVersionMatches(locked.markRevoked(), locked.version()).orElseThrow();
                        return null;
                    });
                } finally {
                    manager.close();
                }
            });
            assertTrue(firstLocked.await(20, TimeUnit.SECONDS));

            Future<Optional<AccessGrant>> second = workers.submit(() -> {
                EntityManager manager = factory.createEntityManager();
                try {
                    secondAttempted.countDown();
                    return new JpaAccessRequestTransaction(manager).execute(() ->
                            revocationRepository(manager).findByRequestIdForUpdate("realm-1", authorized.requestId()));
                } finally {
                    manager.close();
                }
            });
            assertTrue(secondAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(500, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            first.get(20, TimeUnit.SECONDS);
            AccessGrant afterFirstCommit = second.get(20, TimeUnit.SECONDS).orElseThrow();
            assertEquals(GrantRevocationState.REVOKED, afterFirstCommit.revocationState());
            assertEquals(1, afterFirstCommit.version());
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    private static AccessGrantRevocationRepository revocationRepository(EntityManager manager) {
        return assertInstanceOf(AccessGrantRevocationRepository.class, new JpaAccessGrantRepository(manager));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
