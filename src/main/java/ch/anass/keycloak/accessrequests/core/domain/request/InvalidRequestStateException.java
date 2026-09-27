package ch.anass.keycloak.accessrequests.core.domain.request;

public final class InvalidRequestStateException extends RuntimeException {

    public InvalidRequestStateException(DecisionStatus currentStatus) {
        super("Request cannot transition from state " + currentStatus + ".");
    }
}
