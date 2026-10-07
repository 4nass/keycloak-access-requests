package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestEventEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestEntity;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalAssuranceEvidence;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        transaction(() -> {
            entityManager.createQuery("delete from AccessRequestEventEntity").executeUpdate();
            entityManager.createQuery("delete from AccessRequestEntity").executeUpdate();
        });
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
    void persistsCriticalApprovalAssuranceEvidence() {
        ApprovalAssuranceEvidence evidence = new ApprovalAssuranceEvidence("2", 2, 300,
                "3", 3, 1_000, 1_010, 1_020);
        AccessRequestEvent event = AccessRequestEvent.rehydrate("critical-event", "critical-request", "realm-1",
                AccessRequestEventType.REQUEST_APPROVED, "approver-1", Instant.ofEpochSecond(1_020),
                "Approved.", null, 1L, null, evidence);
        transaction(() -> new JpaAccessRequestEventPublisher(entityManager).publish(event));
        entityManager.clear();

        AccessRequestEvent verified = new JpaAccessRequestHistoryReader(entityManager)
                .findByRequestId("realm-1", "critical-request").getFirst();
        assertEquals(evidence, verified.assuranceEvidence());
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

    @Test
    void ordersRevocationFailuresByPersistedAttemptWhenTimestampsTie() {
        Instant occurredAt = Instant.parse("2026-09-03T10:05:00Z");
        transaction(() -> {
            // The random event IDs deliberately sort opposite to the actual attempt order.
            entityManager.persist(new AccessRequestEventEntity(AccessRequestEvent.rehydrate(
                    "event-a", "request-1", "realm-1", AccessRequestEventType.REVOCATION_FAILED,
                    "worker", occurredAt, null, GrantRevocationFailureCode.REMOVAL_FAILED.name(), null, 2L)));
            entityManager.persist(new AccessRequestEventEntity(AccessRequestEvent.rehydrate(
                    "event-z", "request-1", "realm-1", AccessRequestEventType.REVOCATION_FAILED,
                    "worker", occurredAt, null,
                    GrantRevocationFailureCode.AUTHORITY_UNVERIFIABLE.name(), null, 1L)));
        });
        entityManager.clear();

        JpaAccessRequestHistoryReader reader = new JpaAccessRequestHistoryReader(entityManager);
        assertEquals(List.of("event-z", "event-a"), reader.findByRequestId("realm-1", "request-1")
                .stream().map(AccessRequestEvent::id).toList());
        assertEquals(List.of("event-z", "event-a"), reader.findPageByRequestId("realm-1", "request-1", 0, 20)
                .items().stream().map(AccessRequestEvent::id).toList());
        assertEquals(List.of("event-a", "event-z"), reader.findAll("realm-1", null, null, null,
                null, null, "request-1", 0, 20).items().stream().map(AccessRequestEvent::id).toList());
    }

    @Test
    void filtersByRequesterBeforePaginationIndependentlyOfTheEventActor() {
        transaction(() -> {
            entityManager.persist(AccessRequestEntity.from(request("request-a", "realm-1", "requester-a")));
            entityManager.persist(AccessRequestEntity.from(request("request-b", "realm-1", "requester-b")));
            entityManager.persist(AccessRequestEntity.from(request("request-c", "realm-2", "requester-a")));
            entityManager.persist(new AccessRequestEventEntity(event("event-a", "request-a", "realm-1",
                    AccessRequestEventType.REQUEST_APPROVED, "2026-09-03T10:00:00Z", null)));
            entityManager.persist(new AccessRequestEventEntity(event("event-b", "request-b", "realm-1",
                    AccessRequestEventType.REQUEST_APPROVED, "2026-09-03T10:01:00Z", null)));
            entityManager.persist(new AccessRequestEventEntity(event("event-c", "request-c", "realm-2",
                    AccessRequestEventType.REQUEST_APPROVED, "2026-09-03T10:02:00Z", null)));
        });
        entityManager.clear();

        JpaAccessRequestHistoryReader reader = new JpaAccessRequestHistoryReader(entityManager);
        var byRequester = reader.findAll("realm-1", null, null, null, "requester-a", null, null, 0, 1);
        assertEquals(1, byRequester.total());
        assertEquals(List.of("event-a"), byRequester.items().stream().map(AccessRequestEvent::id).toList());
        assertEquals(0, reader.findAll("realm-1", null, null, null, "requester-a", "someone-else", null, 0, 1)
                .total());
        assertEquals(2, reader.findAll("realm-1", null, null, null, null, "actor-1", null, 0, 1).total());
    }

    private static AccessRequest request(String id, String realmId, String requesterId) {
        return AccessRequest.create(id, realmId, requesterId, "entitlement-1", ResourceType.REALM_ROLE,
                "role-1", "Role", "Needed for a project");
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
