package ch.anass.keycloak.accessrequests.core.service;

public final class InvalidRequestedDurationException extends RuntimeException {

    public InvalidRequestedDurationException() {
        super("Requested access duration is not allowed for this entitlement.");
    }
}
