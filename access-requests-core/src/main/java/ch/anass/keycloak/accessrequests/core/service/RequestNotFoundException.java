package ch.anass.keycloak.accessrequests.core.service;

public final class RequestNotFoundException extends RuntimeException {

    public RequestNotFoundException(String requestId) {
        super("Request not found: " + requestId);
    }
}
