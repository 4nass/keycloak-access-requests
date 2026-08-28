package ch.anass.keycloak.accessrequests.core.service;

public final class EntitlementNotFoundException extends RuntimeException {

    public EntitlementNotFoundException(String entitlementId) {
        super("Entitlement not found: " + entitlementId);
    }
}
