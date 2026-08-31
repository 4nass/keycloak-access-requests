package ch.anass.keycloak.accessrequests.core.domain;

public class UnauthorizedRequestActionException extends RuntimeException {

    public UnauthorizedRequestActionException(String message) {
        super(message);
    }
}
