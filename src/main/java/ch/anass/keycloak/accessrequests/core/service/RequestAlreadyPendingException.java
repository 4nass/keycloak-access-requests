package ch.anass.keycloak.accessrequests.core.service;

public final class RequestAlreadyPendingException extends RuntimeException {

    public RequestAlreadyPendingException(String entitlementId) {
        super("A pending request already exists for entitlement: " + entitlementId);
    }
}
