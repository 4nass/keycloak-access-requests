package ch.anass.keycloak.accessrequests.core.domain.grant;

/**
 * Revocation authority is separate from the historical origin of a grant.
 */
public enum GrantRevocationState {
    UNVERIFIED,
    AUTHORIZED,
    INVALIDATED,
    REVOKED
}
