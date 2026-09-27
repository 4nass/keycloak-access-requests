package ch.anass.keycloak.accessrequests.core.domain.grant;

/**
 * Whether provisioning created a direct assignment or found access already in place.
 */
public enum GrantOrigin {
    CREATED_BY_EXTENSION,
    PREEXISTING
}
