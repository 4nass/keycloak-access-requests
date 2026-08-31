package ch.anass.keycloak.accessrequests.core.domain;

public final class UnauthorizedApprovalException extends UnauthorizedRequestActionException {

    public UnauthorizedApprovalException() {
        super("The actor cannot decide this request.");
    }
}
