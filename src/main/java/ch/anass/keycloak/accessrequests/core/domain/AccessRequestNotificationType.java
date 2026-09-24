package ch.anass.keycloak.accessrequests.core.domain;

/**
 * User-facing access request lifecycle notifications.
 */
public enum AccessRequestNotificationType {
    REQUEST_SUBMITTED,
    REQUEST_APPROVED,
    REQUEST_REJECTED,
    PROVISIONING_FAILED,
    PROVISIONING_CLOSED
}
