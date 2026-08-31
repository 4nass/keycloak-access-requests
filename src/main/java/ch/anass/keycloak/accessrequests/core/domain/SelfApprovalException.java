package ch.anass.keycloak.accessrequests.core.domain;

public final class SelfApprovalException extends UnauthorizedRequestActionException {

    public SelfApprovalException() {
        super("A requester cannot decide their own request.");
    }
}
