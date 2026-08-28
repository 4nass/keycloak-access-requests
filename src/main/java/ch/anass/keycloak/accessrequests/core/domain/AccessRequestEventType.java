package ch.anass.keycloak.accessrequests.core.domain;

public enum AccessRequestEventType {
    REQUEST_CREATED,
    REQUEST_CANCELED,
    REQUEST_APPROVED,
    REQUEST_REJECTED,
    PROVISIONING_STARTED,
    PROVISIONING_SUCCEEDED,
    PROVISIONING_FAILED
}
