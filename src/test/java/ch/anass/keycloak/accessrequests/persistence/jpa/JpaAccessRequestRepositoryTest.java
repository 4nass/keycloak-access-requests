package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.core.port.ApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.DuplicatePendingRequestException;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.service.RequestPolicy;
import ch.anass.keycloak.accessrequests.core.service.RequestService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaAccessRequestRepositoryTest {

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
        clearDatabase();
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void persistsAndReloadsRequestWithInitialVersion() {
        AccessRequest request = request("realm-persist", "requester-1", "request-1");
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);

        AccessRequest persisted = transaction().execute(() -> repository.createIfNoPending(request).orElseThrow());
        AccessRequest reloaded = repository.findById("realm-persist", request.id()).orElseThrow();

        assertEquals(0, persisted.version());
        assertEquals(0, reloaded.version());
        assertEquals(request.id(), reloaded.id());
        assertEquals(DecisionStatus.PENDING, reloaded.decisionStatus());
    }

    @Test
    void preservesLifecycleMetadataAndLongDecisionComments() {
        Instant createdAt = Instant.parse("2026-08-29T10:15:30Z");
        Instant decidedAt = Instant.parse("2026-08-29T10:20:30Z");
        String longComment = "Approved after a detailed review. ".repeat(200);
        AccessRequest request = AccessRequest.create(
                "request-lifecycle",
                "realm-lifecycle",
                "requester-1",
                "entitlement-1",
                ResourceType.REALM_ROLE,
                "resource-1",
                "Resource",
                "Access is needed for the project.",
                createdAt);
        request.approve("approver-1", longComment, decidedAt);
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);

        transaction().execute(() -> {
            AccessRequest persisted = repository.createIfNoPending(request).orElseThrow();
            new JpaAccessRequestEventPublisher(entityManager).publish(
                    AccessRequestEvent.approved(persisted, "approver-1", decidedAt, longComment));
            return persisted;
        });
        AccessRequest reloaded = repository.findById("realm-lifecycle", request.id()).orElseThrow();

        assertEquals(DecisionStatus.APPROVED, reloaded.decisionStatus());
        assertEquals(longComment, reloaded.decisionComment());
        assertEquals(createdAt, reloaded.createdAt());
        assertEquals(decidedAt, reloaded.updatedAt());
        assertEquals(decidedAt, reloaded.decidedAt());
        assertEquals(1, countEvents("realm-lifecycle"));
        assertEquals(longComment, entityManager.createNativeQuery(
                        "select COMMENT from AR_ACCESS_REQUEST_HISTORY where REALM_ID = 'realm-lifecycle'")
                .getSingleResult());
    }

    @Test
    void databaseConstraintRejectsDuplicatePendingRequests() {
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);
        transaction().execute(() -> repository.createIfNoPending(
                request("realm-duplicate", "requester-1", "request-1")).orElseThrow());

        assertThrows(DuplicatePendingRequestException.class, () -> transaction().execute(() ->
                repository.createIfNoPending(request("realm-duplicate", "requester-1", "request-2"))));

        assertEquals(1, countRequests("realm-duplicate"));
    }

    @Test
    void databaseConstraintAllowsNewRequestAfterTerminalState() {
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);
        AccessRequest first = transaction().execute(() -> repository.createIfNoPending(
                request("realm-terminal", "requester-1", "request-1")).orElseThrow());
        AccessRequest canceled = first.copy();
        canceled.cancel("requester-1");
        transaction().execute(() -> repository.updateIfVersionMatches(canceled, first.version()).orElseThrow());

        AccessRequest replacement = transaction().execute(() -> repository.createIfNoPending(
                request("realm-terminal", "requester-1", "request-2")).orElseThrow());

        assertEquals(DecisionStatus.PENDING, replacement.decisionStatus());
        assertEquals(2, countRequests("realm-terminal"));
    }

    @Test
    void persistsDecisionLifecycleMetadataWhenUpdatingARequest() {
        Instant createdAt = Instant.parse("2026-08-29T10:15:30Z");
        Instant decidedAt = Instant.parse("2026-08-29T10:20:30Z");
        AccessRequest request = AccessRequest.create(
                "request-decision-metadata",
                "realm-decision-metadata",
                "requester-1",
                "entitlement-1",
                ResourceType.REALM_ROLE,
                "resource-1",
                "Resource",
                "Access is needed for the project.",
                createdAt);
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);
        AccessRequest persisted = transaction().execute(() -> repository.createIfNoPending(request).orElseThrow());
        AccessRequest approved = persisted.copy();
        approved.approve("approver-1", "Approved for the project.", decidedAt);

        AccessRequest reloaded = transaction().execute(
                () -> repository.updateIfVersionMatches(approved, persisted.version()).orElseThrow());

        assertEquals(DecisionStatus.APPROVED, reloaded.decisionStatus());
        assertEquals(ProvisioningStatus.NOT_STARTED, reloaded.provisioningStatus());
        assertEquals(decidedAt, reloaded.updatedAt());
        assertEquals(decidedAt, reloaded.decidedAt());
    }

    @Test
    void findsOnlyPendingRequestsForTheGivenRequesterAndEntitlements() {
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);
        AccessRequest pending = request("realm-catalog", "requester-1", "request-pending");
        AccessRequest approved = request("realm-catalog", "requester-1", "request-approved");
        approved.approve("approver-1", "Approved.");
        AccessRequest anotherRequester = request("realm-catalog", "requester-2", "request-other-requester");
        AccessRequest anotherRealm = request("realm-other", "requester-1", "request-other-realm");

        transaction().execute(() -> {
            repository.createIfNoPending(pending).orElseThrow();
            repository.createIfNoPending(approved).orElseThrow();
            repository.createIfNoPending(anotherRequester).orElseThrow();
            repository.createIfNoPending(anotherRealm).orElseThrow();
            return null;
        });

        assertEquals(Set.of("entitlement-1"), repository.findPendingEntitlementIds(
                "realm-catalog", "requester-1", Set.of("entitlement-1", "other-entitlement")));
        assertEquals(Set.of(), repository.findPendingEntitlementIds(
                "realm-catalog", "requester-1", Set.of()));
    }

    @Test
    void pagesRequestsWithinTheRequesterAndRealmBoundary() {
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);
        AccessRequest first = request("realm-requests", "requester-1", "request-1", "entitlement-1");
        AccessRequest second = request("realm-requests", "requester-1", "request-2", "entitlement-2");
        AccessRequest anotherRequester = request("realm-requests", "requester-2", "request-3", "entitlement-3");
        AccessRequest anotherRealm = request("realm-other", "requester-1", "request-4", "entitlement-4");
        transaction().execute(() -> {
            repository.createIfNoPending(first).orElseThrow();
            repository.createIfNoPending(second).orElseThrow();
            repository.createIfNoPending(anotherRequester).orElseThrow();
            repository.createIfNoPending(anotherRealm).orElseThrow();
            return null;
        });

        AccessRequestPage firstPage = repository.findByRequester(
                new AccessRequestQuery("realm-requests", "requester-1", 0, 1));
        AccessRequestPage secondPage = repository.findByRequester(
                new AccessRequestQuery("realm-requests", "requester-1", 1, 1));

        assertEquals(2, firstPage.total());
        assertEquals(2, secondPage.total());
        assertEquals(1, firstPage.items().size());
        assertEquals(1, secondPage.items().size());
        assertFalse(firstPage.items().get(0).id().equals(secondPage.items().get(0).id()));
    }

    @Test
    void pagesOnlyPendingRequestsWithinTheApproversEntitlementScope() {
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);
        AccessRequest eligibleOld = requestAt("realm-queue", "requester-1", "eligible-old", "finance", 1);
        AccessRequest ineligibleNew = requestAt("realm-queue", "requester-2", "ineligible-new", "hr", 5);
        AccessRequest eligibleMiddle = requestAt("realm-queue", "requester-3", "eligible-middle", "finance", 3);
        AccessRequest eligibleNew = requestAt("realm-queue", "requester-4", "eligible-new", "finance", 4);
        AccessRequest ownRequest = requestAt("realm-queue", "approver-1", "own-request", "finance", 6);
        AccessRequest decided = requestAt("realm-queue", "requester-5", "decided", "finance", 2);
        decided.approve("approver-2", "Approved.", Instant.ofEpochSecond(7));

        transaction().execute(() -> {
            entityManager.persist(EntitlementEntity.from(entitlement("realm-queue", "finance", "finance-role")));
            entityManager.persist(EntitlementEntity.from(entitlement("realm-queue", "hr", "hr-role")));
            repository.createIfNoPending(eligibleOld).orElseThrow();
            repository.createIfNoPending(ineligibleNew).orElseThrow();
            repository.createIfNoPending(eligibleMiddle).orElseThrow();
            repository.createIfNoPending(eligibleNew).orElseThrow();
            repository.createIfNoPending(ownRequest).orElseThrow();
            repository.createIfNoPending(decided).orElseThrow();
            return null;
        });

        AccessRequestPage page = repository.findPendingForApprover(
                new ApprovalQueueQuery("realm-queue", "approver-1", Set.of("finance-role"), 1, 1));

        assertEquals(List.of("eligible-middle"), page.items().stream().map(AccessRequest::id).toList());
        assertEquals(3, page.total());
        assertEquals(1, page.page());
        assertEquals(1, page.size());
    }

    @Test
    void concurrentCreatesAllowOnlyOnePendingRequest() throws Exception {
        String realmId = "realm-concurrent-" + UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = List.of(
                    executor.submit(() -> createConcurrently(start, request(realmId, "requester-1", "request-1"))),
                    executor.submit(() -> createConcurrently(start, request(realmId, "requester-1", "request-2"))));
            start.countDown();

            int successfulCreates = 0;
            int duplicateFailures = 0;
            for (Future<Boolean> future : futures) {
                try {
                    if (future.get(10, TimeUnit.SECONDS)) {
                        successfulCreates++;
                    }
                } catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof DuplicatePendingRequestException);
                    duplicateFailures++;
                }
            }

            assertEquals(1, successfulCreates);
            assertEquals(1, duplicateFailures);
            assertEquals(1, countRequests(realmId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void optimisticLockRejectsStaleConcurrentUpdate() {
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);
        AccessRequest request = transaction().execute(() -> repository.createIfNoPending(
                request("realm-version", "requester-1", "request-1")).orElseThrow());

        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();
        try {
            JpaAccessRequestRepository firstRepository = new JpaAccessRequestRepository(firstEntityManager);
            JpaAccessRequestRepository secondRepository = new JpaAccessRequestRepository(secondEntityManager);
            AccessRequest firstRead = firstRepository.findById("realm-version", request.id()).orElseThrow();
            AccessRequest secondRead = secondRepository.findById("realm-version", request.id()).orElseThrow();
            AccessRequest firstUpdate = firstRead.copy();
            firstUpdate.cancel("requester-1");
            AccessRequest secondUpdate = secondRead.copy();
            secondUpdate.reject("approver-1", "Rejected after the first update.");

            AccessRequest firstPersisted = new JpaAccessRequestTransaction(firstEntityManager).execute(
                    () -> firstRepository.updateIfVersionMatches(firstUpdate, firstRead.version()).orElseThrow());
            Optional<AccessRequest> staleResult = new JpaAccessRequestTransaction(secondEntityManager).execute(
                    () -> secondRepository.updateIfVersionMatches(secondUpdate, secondRead.version()));

            assertEquals(DecisionStatus.CANCELED, firstPersisted.decisionStatus());
            assertEquals(1, firstPersisted.version());
            assertTrue(staleResult.isEmpty());
        } finally {
            firstEntityManager.close();
            secondEntityManager.close();
        }
    }

    @Test
    void auditEventAndRequestCommitTogether() {
        RequestService service = service(new JpaAccessRequestEventPublisher(entityManager));

        service.create("realm-audit", "requester-1", "entitlement-1", "Access is needed for auditing.");

        assertEquals(1, countRequests("realm-audit"));
        assertEquals(1, countEvents("realm-audit"));
    }

    @Test
    void auditFailureRollsBackRequestAndEvent() {
        JpaAccessRequestEventPublisher delegate = new JpaAccessRequestEventPublisher(entityManager);
        AccessRequestEventPublisher failingPublisher = event -> {
            delegate.publish(event);
            throw new IllegalStateException("Audit persistence failed");
        };
        RequestService service = service(failingPublisher);

        assertThrows(IllegalStateException.class, () -> service.create(
                "realm-audit-failure", "requester-1", "entitlement-1", "Access is needed for auditing."));

        assertEquals(0, countRequests("realm-audit-failure"));
        assertEquals(0, countEvents("realm-audit-failure"));
    }

    private boolean createConcurrently(CountDownLatch start, AccessRequest request) throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        EntityManager manager = entityManagerFactory.createEntityManager();
        try {
            JpaAccessRequestRepository repository = new JpaAccessRequestRepository(manager);
            new JpaAccessRequestTransaction(manager).execute(() -> repository.createIfNoPending(request).orElseThrow());
            return true;
        } finally {
            manager.close();
        }
    }

    private RequestService service(AccessRequestEventPublisher publisher) {
        Entitlement entitlement = Entitlement.create(
                "entitlement-1",
                "realm-audit",
                ResourceType.REALM_ROLE,
                "finance-reader",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "access-request-approver",
                Instant.EPOCH).publish(Instant.EPOCH);
        EntitlementRepository entitlementRepository = new EntitlementRepository() {
            @Override
            public Optional<Entitlement> findById(String realmId, String entitlementId) {
                return Optional.of(Entitlement.rehydrate(
                        entitlement.id(),
                        realmId,
                        entitlement.resourceType(),
                        entitlement.resourceId(),
                        entitlement.displayName(),
                        entitlement.description(),
                        entitlement.riskLevel(),
                        entitlement.approverRoleId(),
                        entitlement.requestable(),
                        entitlement.createdAt(),
                        entitlement.updatedAt(),
                        entitlement.version()));
            }

            @Override
            public CatalogPage findRequestable(CatalogQuery query) {
                throw new UnsupportedOperationException("Catalog reads are not used by this test double.");
            }
        };
        return new RequestService(
                entitlementRepository,
                new JpaAccessRequestRepository(entityManager),
                (realmId, requesterId, requestedEntitlement) -> false,
                (realmId, requesterId) -> true,
                new RequestPolicy(10, 2000),
                publisher,
                (realmId, actorId, entitlementId) -> true,
                new JpaAccessRequestTransaction(entityManager));
    }

    private AccessRequest request(String realmId, String requesterId, String requestId) {
        return request(realmId, requesterId, requestId, "entitlement-1");
    }

    private AccessRequest request(String realmId, String requesterId, String requestId, String entitlementId) {
        return AccessRequest.create(
                requestId,
                realmId,
                requesterId,
                entitlementId,
                ResourceType.REALM_ROLE,
                "resource-1",
                "Resource",
                "Access is needed for the project.");
    }

    private AccessRequest requestAt(
            String realmId,
            String requesterId,
            String requestId,
            String entitlementId,
            long createdAtSeconds) {
        return AccessRequest.create(
                requestId,
                realmId,
                requesterId,
                entitlementId,
                ResourceType.REALM_ROLE,
                "resource-" + entitlementId,
                "Resource " + entitlementId,
                "Access is needed for the project.",
                Instant.ofEpochSecond(createdAtSeconds));
    }

    private Entitlement entitlement(String realmId, String entitlementId, String approverRoleId) {
        return Entitlement.create(
                        entitlementId,
                        realmId,
                        ResourceType.REALM_ROLE,
                        "resource-" + entitlementId,
                        "Resource " + entitlementId,
                        "Access to resource " + entitlementId + ".",
                        RiskLevel.LOW,
                        approverRoleId,
                        Instant.EPOCH)
                .publish(Instant.EPOCH);
    }

    private JpaAccessRequestTransaction transaction() {
        return new JpaAccessRequestTransaction(entityManager);
    }

    private void clearDatabase() {
        EntityTransactionSupport.execute(entityManager, () -> {
            entityManager.createQuery("delete from AccessRequestEventEntity").executeUpdate();
            entityManager.createQuery("delete from AccessRequestEntity").executeUpdate();
            entityManager.createQuery("delete from EntitlementEntity").executeUpdate();
        });
    }

    private long countRequests(String realmId) {
        return (long) entityManager.createQuery(
                        "select count(entity) from AccessRequestEntity entity where entity.realmId = :realmId")
                .setParameter("realmId", realmId)
                .getSingleResult();
    }

    private long countEvents(String realmId) {
        return (long) entityManager.createQuery(
                        "select count(event) from AccessRequestEventEntity event where event.realmId = :realmId")
                .setParameter("realmId", realmId)
                .getSingleResult();
    }

    private static final class EntityTransactionSupport {

        private static void execute(EntityManager entityManager, Runnable operation) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            try {
                operation.run();
                transaction.commit();
            } catch (RuntimeException | Error exception) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw exception;
            }
        }
    }
}
