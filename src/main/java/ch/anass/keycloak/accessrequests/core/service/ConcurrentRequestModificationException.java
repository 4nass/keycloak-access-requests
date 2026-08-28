package ch.anass.keycloak.accessrequests.core.service;

public final class ConcurrentRequestModificationException extends RuntimeException {

    public ConcurrentRequestModificationException(String requestId) {
        super("Request was modified concurrently: " + requestId);
    }
}
