package ch.anass.keycloak.accessrequests.core.domain.approval;

import ch.anass.keycloak.accessrequests.core.domain.request.UnauthorizedRequestActionException;
public final class UnauthorizedApprovalException extends UnauthorizedRequestActionException {

    public UnauthorizedApprovalException() {
        super("The actor cannot decide this request.");
    }
}
