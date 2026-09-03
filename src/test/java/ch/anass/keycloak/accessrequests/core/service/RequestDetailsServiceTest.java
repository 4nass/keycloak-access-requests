package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestDetailsServiceTest {

    @Test
    void returnsTheOwnersRequestWithItsImmutableAuditHistory() {
        AccessRequest request = request("request-1", "requester-1");
        List<AccessRequestEvent> history = List.of(
                AccessRequestEvent.created(request, "requester-1", Instant.parse("2026-09-03T10:00:00Z")),
                AccessRequestEvent.approved(request, "approver-1", Instant.parse("2026-09-03T10:05:00Z"), "Approved."));
        RequestDetailsService service = new RequestDetailsService(
                new StubAccessRequestRepository(request),
                (realmId, requestId) -> history);

        var details = service.findForRequester("realm-1", "requester-1", "request-1");

        assertEquals("I need month-end reports.", details.request().justification());
        assertEquals(history, details.history());
        assertThrows(UnsupportedOperationException.class, () -> details.history().add(history.getFirst()));
    }

    @Test
    void treatsAnotherRequestersRequestAsNotFoundWithoutReadingItsHistory() {
        AtomicBoolean historyRead = new AtomicBoolean();
        RequestDetailsService service = new RequestDetailsService(
                new StubAccessRequestRepository(request("request-1", "requester-1")),
                (realmId, requestId) -> {
                    historyRead.set(true);
                    return List.of();
                });

        assertThrows(RequestNotFoundException.class, () ->
                service.findForRequester("realm-1", "requester-2", "request-1"));

        assertEquals(false, historyRead.get());
    }

    private static AccessRequest request(String requestId, String requesterId) {
        return AccessRequest.create(
                requestId,
                "realm-1",
                requesterId,
                "entitlement-1",
                ResourceType.CLIENT_ROLE,
                "finance-reader",
                "Finance Reader",
                "I need month-end reports.",
                Instant.parse("2026-09-03T10:00:00Z"));
    }

    private static final class StubAccessRequestRepository implements AccessRequestRepository {

        private final AccessRequest request;

        private StubAccessRequestRepository(AccessRequest request) {
            this.request = request;
        }

        @Override
        public Optional<AccessRequest> findById(String realmId, String requestId) {
            return request.realmId().equals(realmId) && request.id().equals(requestId)
                    ? Optional.of(request)
                    : Optional.empty();
        }

        @Override
        public AccessRequestPage findByRequester(AccessRequestQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApprovalQueuePage findPendingForApprover(ApprovalQueueQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> findPendingEntitlementIds(String realmId, String requesterId, Set<String> entitlementIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AccessRequest> createIfNoPending(AccessRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AccessRequest> updateIfVersionMatches(AccessRequest request, long expectedVersion) {
            throw new UnsupportedOperationException();
        }
    }
}
