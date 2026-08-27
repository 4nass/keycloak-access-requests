package io.github.keycloakaccessrequests.core.service;

import io.github.keycloakaccessrequests.core.domain.AccessRequest;
import io.github.keycloakaccessrequests.core.domain.DecisionStatus;
import io.github.keycloakaccessrequests.core.domain.Entitlement;
import io.github.keycloakaccessrequests.core.domain.ResourceType;
import io.github.keycloakaccessrequests.core.domain.UnauthorizedRequestActionException;
import io.github.keycloakaccessrequests.core.port.AccessRequestRepository;
import io.github.keycloakaccessrequests.core.port.EffectiveAccessChecker;
import io.github.keycloakaccessrequests.core.port.EntitlementRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestServiceTest {

    private final InMemoryEntitlementRepository entitlements = new InMemoryEntitlementRepository();
    private final InMemoryAccessRequestRepository requests = new InMemoryAccessRequestRepository();
    private final InMemoryEffectiveAccessChecker effectiveAccess = new InMemoryEffectiveAccessChecker();
    private final RequestService service = new RequestService(
            entitlements,
            requests,
            effectiveAccess,
            new RequestPolicy(10, 2000));

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
        assertEquals(1, requests.saved().size());
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
    void enforcesJustificationLengthBounds() {
        entitlements.add(financeEntitlement());

        assertThrows(InvalidJustificationException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Too short."));

        assertThrows(InvalidJustificationException.class, () -> service.create(
                "realm-1",
                "requester-1",
                "entitlement-1",
                "x".repeat(2001)));

        assertFalse(requests.hasSavedRequests());
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

        assertEquals(DecisionStatus.CANCELED, request.decisionStatus());
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

    private static Entitlement financeEntitlement() {
        return Entitlement.create(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "finance-reader",
                "Finance Reader",
                true);
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

        @Override
        public Optional<AccessRequest> findById(String realmId, String requestId) {
            return values.stream()
                    .filter(request -> request.realmId().equals(realmId))
                    .filter(request -> request.id().equals(requestId))
                    .findFirst();
        }

        @Override
        public boolean existsPending(String realmId, String requesterId, String entitlementId) {
            return values.stream()
                    .filter(request -> request.realmId().equals(realmId))
                    .filter(request -> request.requesterId().equals(requesterId))
                    .filter(request -> request.entitlementId().equals(entitlementId))
                    .anyMatch(request -> request.decisionStatus() == DecisionStatus.PENDING);
        }

        @Override
        public AccessRequest save(AccessRequest request) {
            values.add(request);
            return request;
        }

        List<AccessRequest> saved() {
            return List.copyOf(values);
        }

        boolean hasSavedRequests() {
            return !values.isEmpty();
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
