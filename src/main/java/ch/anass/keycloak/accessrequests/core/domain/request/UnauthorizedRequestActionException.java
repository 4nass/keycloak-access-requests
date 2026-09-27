package ch.anass.keycloak.accessrequests.core.domain.request;

public class UnauthorizedRequestActionException extends RuntimeException {

    public UnauthorizedRequestActionException(String message) {
        super(message);
    }
}
