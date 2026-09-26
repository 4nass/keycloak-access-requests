package ch.anass.keycloak.accessrequests.core.domain;

/**
 * Whether provisioning created a direct assignment or found access already in place.
 */
public enum GrantOrigin {
    CREATED_BY_EXTENSION,
    PREEXISTING
}
