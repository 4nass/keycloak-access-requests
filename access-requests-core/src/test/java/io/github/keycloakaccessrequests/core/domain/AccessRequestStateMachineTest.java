package io.github.keycloakaccessrequests.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessRequestStateMachineTest {

    @Test
    void newRequestStartsPending() {
        AccessRequest request = pendingRequest();

        assertEquals(DecisionStatus.PENDING, request.decisionStatus());
    }

    @Test
    void newRequestHasNoDecisionMetadata() {
        AccessRequest request = pendingRequest();

        assertNull(request.approverId());
        assertNull(request.decisionComment());
    }

    @Test
    void pendingRequestCanBeApproved() {
        AccessRequest request = pendingRequest();

        request.approve("approver-1", "Approved for the project.");

        assertEquals(DecisionStatus.APPROVED, request.decisionStatus());
        assertEquals("approver-1", request.approverId());
        assertEquals("Approved for the project.", request.decisionComment());
    }

    @Test
    void pendingRequestCanBeRejected() {
        AccessRequest request = pendingRequest();

        request.reject("approver-1", "The justification is not sufficient.");

        assertEquals(DecisionStatus.REJECTED, request.decisionStatus());
        assertEquals("approver-1", request.approverId());
        assertEquals("The justification is not sufficient.", request.decisionComment());
    }

    @Test
    void pendingRequestCanBeCanceledByTheRequester() {
        AccessRequest request = pendingRequest();

        request.cancel("requester-1");

        assertEquals(DecisionStatus.CANCELED, request.decisionStatus());
    }

    @Test
    void terminalRequestCannotTransitionAgain() {
        AccessRequest request = pendingRequest();
        request.reject("approver-1", "The justification is not sufficient.");

        assertThrows(InvalidRequestStateException.class,
                () -> request.approve("approver-2", "Approved anyway."));
        assertThrows(InvalidRequestStateException.class,
                () -> request.cancel("requester-1"));
    }

    @Test
    void approvedRequestCannotBeRejected() {
        AccessRequest request = pendingRequest();
        request.approve("approver-1", "Approved for the project.");

        assertThrows(InvalidRequestStateException.class,
                () -> request.reject("approver-2", "Rejected afterwards."));
    }

    @Test
    void approvedRequestCannotBeCanceled() {
        AccessRequest request = pendingRequest();
        request.approve("approver-1", "Approved for the project.");

        assertThrows(InvalidRequestStateException.class,
                () -> request.cancel("requester-1"));
    }

    @Test
    void rejectedRequestCannotBeApproved() {
        AccessRequest request = pendingRequest();
        request.reject("approver-1", "The justification is not sufficient.");

        assertThrows(InvalidRequestStateException.class,
                () -> request.approve("approver-2", "Approved afterwards."));
    }

    @Test
    void rejectedRequestCannotBeCanceled() {
        AccessRequest request = pendingRequest();
        request.reject("approver-1", "The justification is not sufficient.");

        assertThrows(InvalidRequestStateException.class,
                () -> request.cancel("requester-1"));
    }

    @Test
    void canceledRequestCannotBeApproved() {
        AccessRequest request = pendingRequest();
        request.cancel("requester-1");

        assertThrows(InvalidRequestStateException.class,
                () -> request.approve("approver-1", "Approved afterwards."));
    }

    @Test
    void canceledRequestCannotBeRejected() {
        AccessRequest request = pendingRequest();
        request.cancel("requester-1");

        assertThrows(InvalidRequestStateException.class,
                () -> request.reject("approver-1", "Rejected afterwards."));
    }

    private static AccessRequest pendingRequest() {
        return AccessRequest.create(
                "request-1",
                "realm-1",
                "requester-1",
                "entitlement-1",
                "Access is needed for the finance project.");
    }
}
