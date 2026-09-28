package ch.anass.keycloak.accessrequests.core.service;

public final class InvalidRequestedDurationException extends RuntimeException {

    public InvalidRequestedDurationException() {
        super("Requested access duration is invalid or cannot be represented as an expiry.");
    }
}
