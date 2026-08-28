package ch.anass.keycloak.accessrequests.core.domain;

public final class UnauthorizedRequestActionException extends RuntimeException {

    public UnauthorizedRequestActionException(String message) {
        super(message);
    }
}
