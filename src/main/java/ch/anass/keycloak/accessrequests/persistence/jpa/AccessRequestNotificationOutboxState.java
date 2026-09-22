package ch.anass.keycloak.accessrequests.persistence.jpa;

/**
 * Lifecycle of a durable access request email delivery.
 */
public enum AccessRequestNotificationOutboxState {
    PENDING,
    PROCESSING,
    DELIVERED,
    DISCARDED,
    FAILED
}
