package ch.anass.keycloak.accessrequests.core.service;

public final class ConcurrentGrantModificationException extends RuntimeException {

    public ConcurrentGrantModificationException(String requestId) {
        super("Access grant changed during revocation: " + requestId);
    }
}
