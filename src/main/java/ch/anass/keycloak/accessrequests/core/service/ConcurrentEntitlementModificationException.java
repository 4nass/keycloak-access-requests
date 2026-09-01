package ch.anass.keycloak.accessrequests.core.service;

/**
 * Raised when an administrator changes an entitlement that has been modified by another request.
 */
public final class ConcurrentEntitlementModificationException extends RuntimeException {

    public ConcurrentEntitlementModificationException(String entitlementId) {
        super("Entitlement was modified concurrently: " + entitlementId);
    }
}
