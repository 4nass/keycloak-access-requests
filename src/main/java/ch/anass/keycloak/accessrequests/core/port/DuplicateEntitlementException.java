package ch.anass.keycloak.accessrequests.core.port;

/**
 * Signals that a realm already exposes an entitlement for the same Keycloak resource.
 */
public final class DuplicateEntitlementException extends RuntimeException {

    public DuplicateEntitlementException() {
        super("An entitlement already exists for this Keycloak resource.");
    }
}
