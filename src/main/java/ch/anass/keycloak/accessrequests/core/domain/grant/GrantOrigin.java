package ch.anass.keycloak.accessrequests.core.domain.grant;

/**
 * Whether provisioning created an assignment or group membership, or found access already in place.
 */
public enum GrantOrigin {
    CREATED_BY_EXTENSION,
    PREEXISTING
}
