package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.UnauthorizedRequestActionException;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.UserStatusReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private final RequestService service = new RequestService(
            entitlements,
            requests,
            effectiveAccess,
            users,
            new RequestPolicy(10, 2000),
            events);

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
        entitlements.add(financeEntitlement().asNotRequestable());

        assertThrows(EntitlementNotRequestableException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project."));

        assertFalse(requests.hasSavedRequests());
    }

    @Test
    void doesNotExposeEntitlementFromAnotherRealm() {
        entitlements.add(financeEntitlement().inRealm("realm-2"));

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
        entitlements.add(financeEntitlement().withId("entitlement-min"));
        entitlements.add(financeEntitlement().withId("entitlement-max"));

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
            entitlements.add(Entitlement.create(
                    entitlementId,
                    "realm-1",
                    resourceType,
                    "resource-" + resourceType.name().toLowerCase(),
                    resourceType.name() + " display name",
                    true));

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
        return Entitlement.create(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "finance-reader",
                "Finance Reader",
                true);
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

        private static String key(String realmId, String entitlementId) {
            return realmId + ":" + entitlementId;
        }
    }

    private static final class InMemoryAccessRequestRepository implements AccessRequestRepository {

        private final List<AccessRequest> values = new ArrayList<>();
        private volatile CountDownLatch createAttempts;
        private volatile boolean forceNextUpdateConflict;

        @Override
        public synchronized Optional<AccessRequest> findById(String realmId, String requestId) {
            return values.stream()
                    .filter(request -> request.realmId().equals(realmId))
                    .filter(request -> request.id().equals(requestId))
                    .findFirst();
        }

        @Override
        public Optional<AccessRequest> createIfNoPending(AccessRequest request) {
            CountDownLatch attempts = createAttempts;
            if (attempts != null) {
                attempts.countDown();
                await(attempts);
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

        synchronized boolean hasSavedRequests() {
            return !values.isEmpty();
        }

        void coordinateTwoCreateAttempts() {
            createAttempts = new CountDownLatch(2);
        }

        void forceNextUpdateConflict() {
            forceNextUpdateConflict = true;
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent create attempts were not coordinated");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while coordinating concurrent create attempts", exception);
            }
        }
    }

    private static final class InMemoryAccessRequestEventPublisher implements AccessRequestEventPublisher {

        private final List<AccessRequestEvent> values = new ArrayList<>();

        @Override
        public synchronized void publish(AccessRequestEvent event) {
            values.add(event);
        }

        synchronized List<AccessRequestEvent> published() {
            return List.copyOf(values);
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
