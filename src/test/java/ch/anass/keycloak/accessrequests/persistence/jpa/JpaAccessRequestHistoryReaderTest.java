package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JpaAccessRequestHistoryReaderTest {

    private static EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;

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
        transaction(() -> entityManager.createQuery("delete from AccessRequestEventEntity").executeUpdate());
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void returnsOnlyTheRequestedRealmsHistoryInChronologicalOrder() {
        transaction(() -> {
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-2", "request-1", "realm-1", AccessRequestEventType.REQUEST_APPROVED,
                    "2026-09-03T10:05:00Z", "Approved.")));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-1", "request-1", "realm-1", AccessRequestEventType.REQUEST_CREATED,
                    "2026-09-03T10:00:00Z", null)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-other-realm", "request-1", "realm-2", AccessRequestEventType.REQUEST_CREATED,
                    "2026-09-03T09:00:00Z", null)));
        });
        entityManager.clear();

        List<AccessRequestEvent> history = new JpaAccessRequestHistoryReader(entityManager)
                .findByRequestId("realm-1", "request-1");

        assertEquals(List.of("event-1", "event-2"), history.stream().map(AccessRequestEvent::id).toList());
        assertEquals(List.of(AccessRequestEventType.REQUEST_CREATED, AccessRequestEventType.REQUEST_APPROVED),
                history.stream().map(AccessRequestEvent::type).toList());
        assertEquals("Approved.", history.get(1).comment());
    }

    @Test
    void ordersSameMillisecondRetriesAndClosureByRequestVersionAndLifecyclePhase() {
        String instant = "2026-09-03T10:05:00Z";
        transaction(() -> {
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-a", "request-1", "realm-1", AccessRequestEventType.PROVISIONING_CLOSED,
                    instant, 4L)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-b", "request-1", "realm-1", AccessRequestEventType.PROVISIONING_FAILED,
                    instant, 3L)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-c", "request-1", "realm-1", AccessRequestEventType.PROVISIONING_STARTED,
                    instant, 2L)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-d", "request-1", "realm-1", AccessRequestEventType.PROVISIONING_FAILED,
                    instant, 2L)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-e", "request-1", "realm-1", AccessRequestEventType.PROVISIONING_STARTED,
                    instant, 1L)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-f", "request-1", "realm-1", AccessRequestEventType.REQUEST_APPROVED,
                    instant, 1L)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-g", "request-1", "realm-1", AccessRequestEventType.REQUEST_CREATED,
                    instant, 0L)));
        });
        entityManager.clear();

        List<AccessRequestEventType> types = new JpaAccessRequestHistoryReader(entityManager)
                .findByRequestId("realm-1", "request-1").stream().map(AccessRequestEvent::type).toList();

        assertEquals(List.of(
                AccessRequestEventType.REQUEST_CREATED,
                AccessRequestEventType.REQUEST_APPROVED,
                AccessRequestEventType.PROVISIONING_STARTED,
                AccessRequestEventType.PROVISIONING_FAILED,
                AccessRequestEventType.PROVISIONING_STARTED,
                AccessRequestEventType.PROVISIONING_FAILED,
                AccessRequestEventType.PROVISIONING_CLOSED), types);
    }

    @Test
    void ordersLegacySameMillisecondDecisionStartAndFailureWithoutAStoredVersion() {
        String instant = "2026-09-03T10:05:00Z";
        transaction(() -> {
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-a", "request-1", "realm-1", AccessRequestEventType.PROVISIONING_FAILED,
                    instant, null)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-b", "request-1", "realm-1", AccessRequestEventType.PROVISIONING_STARTED,
                    instant, null)));
            entityManager.persist(new AccessRequestEventEntity(event(
                    "event-z", "request-1", "realm-1", AccessRequestEventType.REQUEST_APPROVED,
                    instant, null)));
        });
        entityManager.clear();

        assertEquals(List.of(AccessRequestEventType.REQUEST_APPROVED, AccessRequestEventType.PROVISIONING_STARTED,
                        AccessRequestEventType.PROVISIONING_FAILED),
                new JpaAccessRequestHistoryReader(entityManager)
                        .findByRequestId("realm-1", "request-1").stream()
                        .map(AccessRequestEvent::type).toList());
    }

    private static AccessRequestEvent event(
            String id,
            String requestId,
            String realmId,
            AccessRequestEventType type,
            String occurredAt,
            String comment) {
        return AccessRequestEvent.rehydrate(
                id,
                requestId,
                realmId,
                type,
                "actor-1",
                Instant.parse(occurredAt),
                comment,
                null);
    }

    private static AccessRequestEvent event(
            String id, String requestId, String realmId, AccessRequestEventType type,
            String occurredAt, long requestVersion) {
        return AccessRequestEvent.rehydrate(
                id, requestId, realmId, type, "actor-1", Instant.parse(occurredAt),
                null, null, requestVersion);
    }

    private void transaction(Runnable work) {
        var transaction = entityManager.getTransaction();
        transaction.begin();
        try {
            work.run();
            transaction.commit();
        } catch (RuntimeException | Error exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw exception;
        }
    }
}
