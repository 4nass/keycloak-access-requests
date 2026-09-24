package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningResult;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.core.port.ApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.DuplicatePendingRequestException;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.EntitlementProvisioner;
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
import java.util.concurrent.TimeoutException;

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
    void pagesOnlyFailedProvisioningRequestsWithinTheRealmAndReturnsOnlyOperationalFields() {
        JpaAccessRequestRepository repository = new JpaAccessRequestRepository(entityManager);
        AccessRequest olderFailure = failedRequest(
                "realm-provisioning", "requester-1", "failed-1", Instant.parse("2026-09-20T10:00:00Z"));
        AccessRequest newerFailure = failedRequest(
                "realm-provisioning", "requester-2", "failed-2", Instant.parse("2026-09-21T10:00:00Z"));
        AccessRequest succeeded = request("realm-provisioning", "requester-3", "succeeded");
        succeeded.approve("approver-1", "Approved.");
        succeeded.markProvisioningSucceeded(Instant.parse("2026-09-22T10:00:00Z"));
        AccessRequest otherRealmFailure = failedRequest(
                "realm-other", "requester-4", "other-realm-failure", Instant.parse("2026-09-23T10:00:00Z"));

        transaction().execute(() -> {
            repository.createIfNoPending(olderFailure).orElseThrow();
            repository.createIfNoPending(newerFailure).orElseThrow();
            repository.createIfNoPending(succeeded).orElseThrow();
            repository.createIfNoPending(otherRealmFailure).orElseThrow();
            JpaAccessRequestEventPublisher events = new JpaAccessRequestEventPublisher(entityManager);
            events.publish(AccessRequestEvent.rehydrate(
                    UUID.randomUUID().toString(), olderFailure.id(), olderFailure.realmId(),
                    AccessRequestEventType.PROVISIONING_FAILED, "approver-1",
                    olderFailure.updatedAt(), "Legacy internal detail", null));
            events.publish(AccessRequestEvent.rehydrate(
                    UUID.randomUUID().toString(), olderFailure.id(), olderFailure.realmId(),
                    AccessRequestEventType.PROVISIONING_FAILED, "approver-1",
                    olderFailure.updatedAt(), "Another same-millisecond legacy failure",
                    ProvisioningFailureCode.RESOURCE_MISSING.name()));
            events.publish(AccessRequestEvent.provisioningFailed(
                    newerFailure, "approver-1", newerFailure.updatedAt().minusSeconds(30),
                    "Earlier internal detail", ProvisioningFailureCode.RESOURCE_MISSING));
            events.publish(AccessRequestEvent.rehydrate(
                    "ffffffff-ffff-ffff-ffff-ffffffffffff", newerFailure.id(), newerFailure.realmId(),
                    AccessRequestEventType.PROVISIONING_FAILED, "approver-1",
                    newerFailure.updatedAt(), "Same-time internal detail",
                    ProvisioningFailureCode.PROVIDER_UNAVAILABLE.name(), 1L));
            events.publish(AccessRequestEvent.rehydrate(
                    "00000000-0000-0000-0000-000000000001", newerFailure.id(), newerFailure.realmId(),
                    AccessRequestEventType.PROVISIONING_FAILED, "approver-1",
                    newerFailure.updatedAt(), "Latest internal detail",
                    ProvisioningFailureCode.UNEXPECTED_FAILURE.name(), 2L));
            events.publish(AccessRequestEvent.rehydrate(
                    UUID.randomUUID().toString(), newerFailure.id(), "realm-other",
                    AccessRequestEventType.PROVISIONING_FAILED, "approver-1",
                    newerFailure.updatedAt().plusSeconds(30), "Other realm detail",
                    ProvisioningFailureCode.REQUESTER_MISSING.name()));
            return null;
        });

        JpaAccessRequestRepository.FailedProvisioningPage firstPage =
                repository.findFailedProvisioning("realm-provisioning", 0, 1);
        JpaAccessRequestRepository.FailedProvisioningPage secondPage =
                repository.findFailedProvisioning("realm-provisioning", 1, 1);

        assertEquals(2, firstPage.total());
        assertEquals(2, secondPage.total());
        assertEquals("failed-2", firstPage.items().get(0).id());
        assertEquals("failed-1", secondPage.items().get(0).id());
        assertEquals(DecisionStatus.APPROVED, firstPage.items().get(0).decisionStatus());
        assertEquals(ProvisioningStatus.FAILED, firstPage.items().get(0).provisioningStatus());
        assertEquals("Resource", firstPage.items().get(0).resourceName());
        assertEquals(ProvisioningFailureCode.UNEXPECTED_FAILURE, firstPage.items().get(0).failureCode());
        assertEquals(ProvisioningFailureCode.UNKNOWN, secondPage.items().get(0).failureCode());
        transaction().execute(() -> {
            AccessRequest closed = repository.findByIdForUpdate("realm-provisioning", newerFailure.id()).orElseThrow();
            closed.closeFailedProvisioning("manager-1", "The original resource was deleted.",
                    Instant.parse("2026-09-23T11:00:00Z"));
            repository.updateIfVersionMatches(closed, newerFailure.version()).orElseThrow();
            return null;
        });
        JpaAccessRequestRepository.FailedProvisioningPage afterClosure =
                repository.findFailedProvisioning("realm-provisioning", 0, 20);
        assertEquals(1, afterClosure.total());
        assertEquals(olderFailure.id(), afterClosure.items().getFirst().id());
        AccessRequest persistedClosure = repository.findById("realm-provisioning", newerFailure.id()).orElseThrow();
        assertEquals("manager-1", persistedClosure.provisioningClosedBy());
        assertEquals("The original resource was deleted.", persistedClosure.provisioningClosureReason());
        assertEquals(Instant.parse("2026-09-23T11:00:00Z"), persistedClosure.provisioningClosedAt());
        JpaAccessRequestRepository.FailedProvisioningPage closedPage =
                repository.findClosedProvisioning("realm-provisioning", 0, 20);
        assertEquals(1, closedPage.total());
        assertEquals(newerFailure.id(), closedPage.items().getFirst().id());
        assertEquals("manager-1", closedPage.items().getFirst().closedBy());
        assertEquals("The original resource was deleted.", closedPage.items().getFirst().closureReason());
        assertEquals(Instant.parse("2026-09-23T11:00:00Z"), closedPage.items().getFirst().closedAt());
        assertEquals(ProvisioningFailureCode.UNEXPECTED_FAILURE, closedPage.items().getFirst().failureCode());
        assertEquals(0, repository.findClosedProvisioning("realm-other", 0, 20).total());
        assertEquals(0, repository.findFailedProvisioning("realm-empty", 0, 20).total());
        assertThrows(IllegalArgumentException.class, () -> repository.findClosedProvisioning("realm", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.findClosedProvisioning("realm", -1, 20));
        assertThrows(IllegalArgumentException.class, () -> repository.findFailedProvisioning("realm", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.findFailedProvisioning("realm", 0, 101));
        assertThrows(IllegalArgumentException.class, () -> repository.findFailedProvisioning("realm", -1, 20));
    }

    private AccessRequest failedRequest(String realmId, String requesterId, String requestId, Instant completedAt) {
        AccessRequest request = request(realmId, requesterId, requestId);
        request.approve("approver-1", "Approved.", completedAt.minusSeconds(60));
        request.markProvisioningFailed(completedAt);
        return request;
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

        ApprovalQueuePage page = repository.findPendingForApprover(
                new ApprovalQueueQuery("realm-queue", "approver-1", Set.of("finance-role"), 1, 1));

        assertEquals(List.of("eligible-middle"), page.items().stream()
                .map(entry -> entry.request().id()).toList());
        assertEquals(RiskLevel.LOW, page.items().getFirst().riskLevel());
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
    void pessimisticRequestLockSerializesConcurrentProvisioningRetries() throws Exception {
        AccessRequest request = transaction().execute(() -> new JpaAccessRequestRepository(entityManager)
                .createIfNoPending(request("realm-retry-lock", "requester-1", "request-1"))
                .orElseThrow());
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondLockAttempted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                EntityManager manager = entityManagerFactory.createEntityManager();
                try {
                    new JpaAccessRequestTransaction(manager).execute(() -> {
                        new JpaAccessRequestRepository(manager)
                                .findByIdForUpdate("realm-retry-lock", request.id())
                                .orElseThrow();
                        firstLockAcquired.countDown();
                        await(releaseFirstTransaction);
                        return null;
                    });
                } finally {
                    manager.close();
                }
            });
            assertTrue(firstLockAcquired.await(10, TimeUnit.SECONDS));

            Future<Optional<AccessRequest>> second = executor.submit(() -> {
                EntityManager manager = entityManagerFactory.createEntityManager();
                try {
                    secondLockAttempted.countDown();
                    return new JpaAccessRequestTransaction(manager).execute(() ->
                            new JpaAccessRequestRepository(manager)
                                    .findByIdForUpdate("realm-retry-lock", request.id()));
                } finally {
                    manager.close();
                }
            });
            assertTrue(secondLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));

            releaseFirstTransaction.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertEquals(request.id(), second.get(10, TimeUnit.SECONDS).orElseThrow().id());
        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();
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
            public Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId) {
                return findById(realmId, entitlementId);
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
                new JpaAccessRequestTransaction(entityManager),
                List.of(new EntitlementProvisioner() {
                    @Override
                    public boolean supports(ResourceType resourceType) {
                        return true;
                    }

                    @Override
                    public ProvisioningResult grant(String realmId, String requesterId, Entitlement entitlement) {
                        return ProvisioningResult.succeeded();
                    }
                }));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent repository transaction.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent repository transaction.",
                    exception);
        }
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
