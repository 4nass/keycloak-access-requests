package ch.anass.keycloak.accessrequests.core.service;

public final class AccessAlreadyGrantedException extends RuntimeException {

    public AccessAlreadyGrantedException(String entitlementId) {
        super("Access is already granted for entitlement: " + entitlementId);
    }
}
