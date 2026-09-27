package ch.anass.keycloak.accessrequests.core.domain.approval;

import ch.anass.keycloak.accessrequests.core.domain.request.UnauthorizedRequestActionException;
public final class SelfApprovalException extends UnauthorizedRequestActionException {

    public SelfApprovalException() {
        super("A requester cannot decide their own request.");
    }
}
