package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.UnauthorizedRequestActionException;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import ch.anass.keycloak.accessrequests.core.port.ApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.UserStatusReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestServiceTest {

    private final InMemoryEntitlementRepository entitlements = new InMemoryEntitlementRepository();
    private final InMemoryAccessRequestRepository requests = new InMemoryAccessRequestRepository();
    private final InMemoryEffectiveAccessChecker effectiveAccess = new InMemoryEffectiveAccessChecker();
    private final InMemoryUserStatusReader users = new InMemoryUserStatusReader();
    private final InMemoryAccessRequestEventPublisher events = new InMemoryAccessRequestEventPublisher();
    private final InMemoryApprovalAuthorizer approvalAuthorizer = new InMemoryApprovalAuthorizer();
    private final InMemoryAccessRequestTransaction transaction =
            new InMemoryAccessRequestTransaction(requests, events);
    private final RequestService service = new RequestService(
            entitlements,
            requests,
            effectiveAccess,
            users,
            new RequestPolicy(10, 2000),
            events,
            approvalAuthorizer,
            transaction);

    @Test
    void createsPendingRequestForRequestableEntitlement() {
        entitlements.add(financeEntitlement());

        AccessRequest created = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");

        assertEquals(DecisionStatus.PENDING, created.decisionStatus());
        assertEquals("realm-1", created.realmId());
        assertEquals("requester-1", created.requesterId());
        assertEquals("entitlement-1", created.entitlementId());
        assertEquals(ResourceType.REALM_ROLE, created.resourceType());
        assertEquals("finance-reader", created.resourceId());
        assertEquals("Finance Reader", created.resourceNameSnapshot());
        assertEquals(1, requests.saved().size());
        assertEquals(1, events.published().size());
        assertEquals(AccessRequestEventType.REQUEST_CREATED, events.published().get(0).type());
        assertEquals(created.id(), events.published().get(0).requestId());
        assertEquals("requester-1", events.published().get(0).actorId());
    }

    @Test
    void listsOnlyTheCurrentRequestersRequestsUsingTheRequestedPage() {
        AccessRequest ownedRequest = AccessRequest.create(
                "request-owned", "realm-1", "requester-1", "entitlement-1", ResourceType.REALM_ROLE,
                "finance-reader", "Finance Reader", "Access is needed for the finance project.");
        AccessRequest otherRequesterRequest = AccessRequest.create(
                "request-other", "realm-1", "requester-2", "entitlement-2", ResourceType.REALM_ROLE,
                "hr-reader", "HR Reader", "Access is needed for the human resources project.");
        requests.add(ownedRequest);
        requests.add(otherRequesterRequest);

        AccessRequestPage page = service.findByRequester(
                new AccessRequestQuery("realm-1", "requester-1", 0, 20));

        assertEquals(List.of(ownedRequest.id()), page.items().stream().map(AccessRequest::id).toList());
        assertEquals(0, page.page());
        assertEquals(20, page.size());
        assertEquals(1, page.total());
    }

    @Test
    void rejectsUnknownEntitlement() {
        assertThrows(EntitlementNotFoundException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "unknown",
                "Access is needed for the finance project."));

        assertFalse(requests.hasSavedRequests());
    }

    @Test
    void rejectsEntitlementThatIsNotRequestable() {
        entitlements.add(financeEntitlement().unpublish(Instant.EPOCH.plusSeconds(1)));

        assertThrows(EntitlementNotRequestableException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project."));

        assertFalse(requests.hasSavedRequests());
    }

    @Test
    void doesNotExposeEntitlementFromAnotherRealm() {
        entitlements.add(entitlement("entitlement-1", "realm-2", ResourceType.REALM_ROLE, "finance-reader",
                "Finance Reader"));

        assertThrows(EntitlementNotFoundException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project."));
    }

    @Test
    void rejectsDisabledUser() {
        entitlements.add(financeEntitlement());
        users.disable("realm-1", "requester-1");

        assertThrows(UserDisabledException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project."));

        assertFalse(requests.hasSavedRequests());
    }

    @Test
    void rejectsRequestWhenAccessIsAlreadyGranted() {
        entitlements.add(financeEntitlement());
        effectiveAccess.grant("realm-1", "requester-1", "entitlement-1");

        assertThrows(AccessAlreadyGrantedException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project."));
    }

    @Test
    void rejectsDuplicatePendingRequest() {
        entitlements.add(financeEntitlement());
        service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");

        assertThrows(RequestAlreadyPendingException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the same finance project."));

        assertEquals(1, requests.saved().size());
    }

    @Test
    void rejectsConcurrentDuplicatePendingRequests() throws Exception {
        entitlements.add(financeEntitlement());
        requests.coordinateTwoCreateAttempts();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<AccessRequest>> futures = List.of(
                    executor.submit(() -> service.create(
                            "realm-1", "requester-1", "entitlement-1", "Access is needed for the finance project.")),
                    executor.submit(() -> service.create(
                            "realm-1", "requester-1", "entitlement-1", "Access is needed for the same finance project.")));

            int successfulRequests = 0;
            int duplicateFailures = 0;
            for (java.util.concurrent.Future<AccessRequest> future : futures) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    successfulRequests++;
                } catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof RequestAlreadyPendingException);
                    duplicateFailures++;
                }
            }

            assertEquals(1, successfulRequests);
            assertEquals(1, duplicateFailures);
            assertEquals(1, requests.saved().size());
            assertEquals(1, events.published().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesJustificationLengthBounds() {
        entitlements.add(financeEntitlement());

        assertThrows(InvalidJustificationException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Too short"));

        assertThrows(InvalidJustificationException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "x".repeat(2001)));

        assertFalse(requests.hasSavedRequests());
    }

    @Test
    void acceptsJustificationAtConfiguredBoundaries() {
        entitlements.add(entitlement("entitlement-min", "realm-1", ResourceType.REALM_ROLE, "finance-reader",
                "Finance Reader"));
        entitlements.add(entitlement("entitlement-max", "realm-1", ResourceType.REALM_ROLE, "finance-reader",
                "Finance Reader"));

        AccessRequest minimum = service.create(
                "realm-1",
                "requester-1",
                "entitlement-min",
                "x".repeat(10));
        AccessRequest maximum = service.create(
                "realm-1",
                "requester-2",
                "entitlement-max",
                "x".repeat(2000));

        assertEquals(DecisionStatus.PENDING, minimum.decisionStatus());
        assertEquals(DecisionStatus.PENDING, maximum.decisionStatus());
    }

    @Test
    void acceptsAllSupportedResourceTypes() {
        for (ResourceType resourceType : ResourceType.values()) {
            String entitlementId = "entitlement-" + resourceType.name().toLowerCase();
            entitlements.add(entitlement(
                    entitlementId,
                    "realm-1",
                    resourceType,
                    "resource-" + resourceType.name().toLowerCase(),
                    resourceType.name() + " display name"));

            AccessRequest created = service.create(
                    "realm-1",
                    "requester-" + resourceType.name().toLowerCase(),
                    entitlementId,
                    "Access is needed for the finance project.");

            assertEquals(resourceType, created.resourceType());
        }
    }

    @Test
    void requesterCanCancelOwnPendingRequest() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");

        service.cancel("realm-1", request.id(), "requester-1");

        AccessRequest persisted = requests.findById("realm-1", request.id()).orElseThrow();
        assertEquals(DecisionStatus.CANCELED, persisted.decisionStatus());
        assertEquals(1, persisted.version());
        assertEquals(AccessRequestEventType.REQUEST_CANCELED, events.published().get(1).type());
        assertEquals("requester-1", events.published().get(1).actorId());
    }

    @Test
    void approverCanApprovePendingRequestAndAuditDecision() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");

        AccessRequest approved = service.approve(
                "realm-1",
                request.id(),
                "approver-1",
                "Approved for the project.");

        assertEquals(DecisionStatus.APPROVED, approved.decisionStatus());
        assertEquals("approver-1", approved.approverId());
        assertEquals("Approved for the project.", approved.decisionComment());
        assertEquals(1, approved.version());
        assertEquals(AccessRequestEventType.REQUEST_APPROVED, events.published().get(1).type());
        assertEquals("Approved for the project.", events.published().get(1).comment());
    }

    @Test
    void approverCanRejectPendingRequestAndAuditDecision() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");

        AccessRequest rejected = service.reject(
                "realm-1",
                request.id(),
                "approver-1",
                "The justification is not sufficient.");

        assertEquals(DecisionStatus.REJECTED, rejected.decisionStatus());
        assertEquals("approver-1", rejected.approverId());
        assertEquals("The justification is not sufficient.", rejected.decisionComment());
        assertEquals(1, rejected.version());
        assertEquals(AccessRequestEventType.REQUEST_REJECTED, events.published().get(1).type());
        assertEquals("The justification is not sufficient.", events.published().get(1).comment());
    }

    @Test
    void requesterCannotApproveOwnRequest() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");

        assertThrows(UnauthorizedRequestActionException.class,
                () -> service.approve("realm-1", request.id(), "requester-1", "Approved."));

        assertEquals(DecisionStatus.PENDING, requests.findById("realm-1", request.id()).orElseThrow().decisionStatus());
        assertEquals(1, events.published().size());
    }

    @Test
    void unauthorizedApproverCannotRejectRequest() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        approvalAuthorizer.deny("realm-1", "approver-1", "entitlement-1");

        assertThrows(UnauthorizedRequestActionException.class,
                () -> service.reject("realm-1", request.id(), "approver-1", "Rejected."));

        assertEquals(DecisionStatus.PENDING, requests.findById("realm-1", request.id()).orElseThrow().decisionStatus());
        assertEquals(1, events.published().size());
    }

    @Test
    void approvalRevalidatesThatEntitlementIsStillRequestable() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        entitlements.add(financeEntitlement().unpublish(Instant.EPOCH.plusSeconds(1)));

        assertThrows(EntitlementNotRequestableException.class,
                () -> service.approve("realm-1", request.id(), "approver-1", "Approved."));

        assertEquals(DecisionStatus.PENDING, requests.findById("realm-1", request.id()).orElseThrow().decisionStatus());
        assertEquals(1, events.published().size());
    }

    @Test
    void rollsBackRequestWhenAuditPublicationFails() {
        entitlements.add(financeEntitlement());
        events.failNextPublication();

        assertThrows(IllegalStateException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project."));

        assertFalse(requests.hasSavedRequests());
        assertTrue(transaction.wasRolledBack());
        assertEquals(0, events.published().size());
    }

    @Test
    void onlyOneOfTwoConcurrentCancellationsCanWin() throws Exception {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        requests.coordinateTwoCancellationReads();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Void>> futures = List.of(
                    executor.submit(() -> {
                        service.cancel("realm-1", request.id(), "requester-1");
                        return null;
                    }),
                    executor.submit(() -> {
                        service.cancel("realm-1", request.id(), "requester-1");
                        return null;
                    }));

            int successfulCancellations = 0;
            int concurrentFailures = 0;
            for (java.util.concurrent.Future<Void> future : futures) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    successfulCancellations++;
                } catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof ConcurrentRequestModificationException);
                    concurrentFailures++;
                }
            }

            AccessRequest persisted = requests.findById("realm-1", request.id()).orElseThrow();
            assertEquals(1, successfulCancellations);
            assertEquals(1, concurrentFailures);
            assertEquals(DecisionStatus.CANCELED, persisted.decisionStatus());
            assertEquals(1, persisted.version());
            assertEquals(2, events.published().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void allowsNewRequestAfterRejection() {
        entitlements.add(financeEntitlement());
        AccessRequest rejected = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        rejected.reject("approver-1", "Please provide more business context.");

        AccessRequest replacement = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the revised finance project.");

        assertEquals(DecisionStatus.PENDING, replacement.decisionStatus());
        assertEquals(2, requests.saved().size());
    }

    @Test
    void allowsNewRequestAfterCancellation() {
        entitlements.add(financeEntitlement());
        AccessRequest canceled = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        service.cancel("realm-1", canceled.id(), "requester-1");

        AccessRequest replacement = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the revised finance project.");

        assertEquals(DecisionStatus.PENDING, replacement.decisionStatus());
        assertEquals(2, requests.saved().size());
    }

    @Test
    void nonRequesterCannotCancelPendingRequest() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");

        assertThrows(UnauthorizedRequestActionException.class,
                () -> service.cancel("realm-1", request.id(), "another-user"));

        assertEquals(DecisionStatus.PENDING, request.decisionStatus());
    }

    @Test
    void cannotCancelUnknownRequest() {
        assertThrows(RequestNotFoundException.class,
                () -> service.cancel("realm-1", "missing-request", "requester-1"));
    }

    @Test
    void cannotCancelApprovedRequest() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        request.approve("approver-1", "Approved for the project.");

        assertThrows(InvalidRequestStateException.class,
                () -> service.cancel("realm-1", request.id(), "requester-1"));
    }

    @Test
    void cannotCancelRejectedRequest() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        request.reject("approver-1", "The justification is not sufficient.");

        assertThrows(InvalidRequestStateException.class,
                () -> service.cancel("realm-1", request.id(), "requester-1"));
    }

    @Test
    void cannotCancelCanceledRequest() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        service.cancel("realm-1", request.id(), "requester-1");

        assertThrows(InvalidRequestStateException.class,
                () -> service.cancel("realm-1", request.id(), "requester-1"));
    }

    @Test
    void doesNotExposeRequestFromAnotherRealmOnCancel() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");

        assertThrows(RequestNotFoundException.class,
                () -> service.cancel("realm-2", request.id(), "requester-1"));
        assertEquals(DecisionStatus.PENDING, request.decisionStatus());
    }

    @Test
    void rejectsCancellationWhenRequestWasModifiedConcurrently() {
        entitlements.add(financeEntitlement());
        AccessRequest request = service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
        requests.forceNextUpdateConflict();

        assertThrows(ConcurrentRequestModificationException.class,
                () -> service.cancel("realm-1", request.id(), "requester-1"));

        AccessRequest persisted = requests.findById("realm-1", request.id()).orElseThrow();
        assertEquals(DecisionStatus.PENDING, persisted.decisionStatus());
        assertEquals(1, events.published().size());
    }

    private static Entitlement financeEntitlement() {
        return entitlement("entitlement-1", "realm-1", ResourceType.REALM_ROLE, "finance-reader",
                "Finance Reader");
    }

    private static Entitlement entitlement(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName) {
        return Entitlement.create(
                        id,
                        realmId,
                        resourceType,
                        resourceId,
                        displayName,
                        "A requestable Keycloak resource.",
                        RiskLevel.LOW,
                        "access-request-approver",
                        Instant.EPOCH)
                .publish(Instant.EPOCH);
    }

    private static final class InMemoryUserStatusReader implements UserStatusReader {

        private final Map<String, Boolean> statuses = new HashMap<>();

        void disable(String realmId, String userId) {
            statuses.put(key(realmId, userId), false);
        }

        @Override
        public boolean isEnabled(String realmId, String userId) {
            return statuses.getOrDefault(key(realmId, userId), true);
        }

        private static String key(String realmId, String userId) {
            return realmId + ":" + userId;
        }
    }

    private static final class InMemoryEntitlementRepository implements EntitlementRepository {

        private final Map<String, Entitlement> values = new HashMap<>();

        void add(Entitlement entitlement) {
            values.put(key(entitlement.realmId(), entitlement.id()), entitlement);
        }

        @Override
        public Optional<Entitlement> findById(String realmId, String entitlementId) {
            return Optional.ofNullable(values.get(key(realmId, entitlementId)));
        }

        @Override
        public CatalogPage findRequestable(CatalogQuery query) {
            throw new UnsupportedOperationException("Catalog reads are not used by this test double.");
        }

        private static String key(String realmId, String entitlementId) {
            return realmId + ":" + entitlementId;
        }
    }

    private static final class InMemoryAccessRequestRepository implements AccessRequestRepository {

        private final List<AccessRequest> values = new ArrayList<>();
        private volatile CountDownLatch createAttempts;
        private volatile CountDownLatch cancellationReads;
        private volatile boolean forceNextUpdateConflict;

        @Override
        public Optional<AccessRequest> findById(String realmId, String requestId) {
            AccessRequest found;
            synchronized (this) {
                found = values.stream()
                        .filter(request -> request.realmId().equals(realmId))
                        .filter(request -> request.id().equals(requestId))
                        .findFirst()
                        .orElse(null);
            }

            CountDownLatch reads = cancellationReads;
            if (found != null && reads != null) {
                reads.countDown();
                await(reads, "cancellation reads");
            }
            return Optional.ofNullable(found).map(AccessRequest::copy);
        }

        @Override
        public synchronized AccessRequestPage findByRequester(AccessRequestQuery query) {
            List<AccessRequest> matching = values.stream()
                    .filter(request -> request.realmId().equals(query.realmId()))
                    .filter(request -> request.requesterId().equals(query.requesterId()))
                    .map(AccessRequest::copy)
                    .toList();
            int from = Math.min(query.offset(), matching.size());
            int to = Math.min(from + query.size(), matching.size());
            return new AccessRequestPage(matching.subList(from, to), query.page(), query.size(), matching.size());
        }

        @Override
        public ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage findPendingForApprover(
                ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery query) {
            throw new UnsupportedOperationException("Approval queue reads are not used by this test double.");
        }

        @Override
        public synchronized Set<String> findPendingEntitlementIds(
                String realmId,
                String requesterId,
                Set<String> entitlementIds) {
            return values.stream()
                    .filter(request -> request.realmId().equals(realmId))
                    .filter(request -> request.requesterId().equals(requesterId))
                    .filter(request -> request.decisionStatus() == DecisionStatus.PENDING)
                    .map(AccessRequest::entitlementId)
                    .filter(entitlementIds::contains)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override
        public Optional<AccessRequest> createIfNoPending(AccessRequest request) {
            CountDownLatch attempts = createAttempts;
            if (attempts != null) {
                attempts.countDown();
                await(attempts, "create attempts");
            }

            synchronized (this) {
                boolean pendingExists = values.stream()
                        .filter(existing -> existing.realmId().equals(request.realmId()))
                        .filter(existing -> existing.requesterId().equals(request.requesterId()))
                        .filter(existing -> existing.entitlementId().equals(request.entitlementId()))
                        .anyMatch(existing -> existing.decisionStatus() == DecisionStatus.PENDING);
                if (pendingExists) {
                    return Optional.empty();
                }
                values.add(request);
                return Optional.of(request);
            }
        }

        @Override
        public synchronized Optional<AccessRequest> updateIfVersionMatches(
                AccessRequest request,
                long expectedVersion) {
            if (forceNextUpdateConflict) {
                forceNextUpdateConflict = false;
                return Optional.empty();
            }

            Optional<AccessRequest> existing = findById(request.realmId(), request.id());
            if (existing.isEmpty() || existing.get().version() != expectedVersion) {
                return Optional.empty();
            }

            AccessRequest persisted = request.withVersion(expectedVersion + 1);
            values.removeIf(current -> current.realmId().equals(request.realmId())
                    && current.id().equals(request.id()));
            values.add(persisted);
            return Optional.of(persisted);
        }

        synchronized List<AccessRequest> saved() {
            return List.copyOf(values);
        }

        synchronized void add(AccessRequest request) {
            values.add(request);
        }

        synchronized boolean hasSavedRequests() {
            return !values.isEmpty();
        }

        void coordinateTwoCreateAttempts() {
            createAttempts = new CountDownLatch(2);
        }

        void coordinateTwoCancellationReads() {
            cancellationReads = new CountDownLatch(2);
        }

        void forceNextUpdateConflict() {
            forceNextUpdateConflict = true;
        }

        synchronized List<AccessRequest> snapshot() {
            return values.stream().map(AccessRequest::copy).toList();
        }

        synchronized void restore(List<AccessRequest> snapshot) {
            values.clear();
            snapshot.stream().map(AccessRequest::copy).forEach(values::add);
        }

        private static void await(CountDownLatch latch, String operation) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent " + operation + " were not coordinated");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while coordinating concurrent " + operation, exception);
            }
        }
    }

    private static final class InMemoryAccessRequestEventPublisher implements AccessRequestEventPublisher {

        private final List<AccessRequestEvent> values = new ArrayList<>();
        private boolean failNextPublication;

        @Override
        public synchronized void publish(AccessRequestEvent event) {
            if (failNextPublication) {
                failNextPublication = false;
                throw new IllegalStateException("Audit publication failed");
            }
            values.add(event);
        }

        synchronized List<AccessRequestEvent> published() {
            return List.copyOf(values);
        }

        synchronized void failNextPublication() {
            failNextPublication = true;
        }

        synchronized List<AccessRequestEvent> snapshot() {
            return List.copyOf(values);
        }

        synchronized void restore(List<AccessRequestEvent> snapshot) {
            values.clear();
            values.addAll(snapshot);
        }
    }

    private static final class InMemoryApprovalAuthorizer implements ApprovalAuthorizer {

        private final Map<String, Boolean> decisions = new HashMap<>();

        void deny(String realmId, String actorId, String entitlementId) {
            decisions.put(key(realmId, actorId, entitlementId), false);
        }

        @Override
        public boolean canDecide(String realmId, String actorId, String entitlementId) {
            return decisions.getOrDefault(key(realmId, actorId, entitlementId), true);
        }

        private static String key(String realmId, String actorId, String entitlementId) {
            return realmId + ":" + actorId + ":" + entitlementId;
        }
    }

    private static final class InMemoryAccessRequestTransaction implements AccessRequestTransaction {

        private final InMemoryAccessRequestRepository requests;
        private final InMemoryAccessRequestEventPublisher events;
        private boolean rolledBack;

        private InMemoryAccessRequestTransaction(
                InMemoryAccessRequestRepository requests,
                InMemoryAccessRequestEventPublisher events) {
            this.requests = requests;
            this.events = events;
        }

        @Override
        public <T> T execute(Supplier<T> operation) {
            List<AccessRequest> requestSnapshot = requests.snapshot();
            List<AccessRequestEvent> eventSnapshot = events.snapshot();
            try {
                return operation.get();
            } catch (RuntimeException | Error exception) {
                if (!(exception instanceof ConcurrentRequestModificationException)
                        && !(exception instanceof RequestAlreadyPendingException)) {
                    requests.restore(requestSnapshot);
                    events.restore(eventSnapshot);
                    rolledBack = true;
                }
                throw exception;
            }
        }

        boolean wasRolledBack() {
            return rolledBack;
        }
    }

    private static final class InMemoryEffectiveAccessChecker implements EffectiveAccessChecker {

        private final List<String> granted = new ArrayList<>();

        void grant(String realmId, String requesterId, String entitlementId) {
            granted.add(key(realmId, requesterId, entitlementId));
        }

        @Override
        public boolean hasAccess(String realmId, String requesterId, Entitlement entitlement) {
            return granted.contains(key(realmId, requesterId, entitlement.id()));
        }

        private static String key(String realmId, String requesterId, String entitlementId) {
            return realmId + ":" + requesterId + ":" + entitlementId;
        }
    }
}
