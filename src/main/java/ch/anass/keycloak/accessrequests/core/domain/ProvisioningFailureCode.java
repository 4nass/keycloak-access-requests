package ch.anass.keycloak.accessrequests.core.domain;

/**
 * Safe, stable diagnostics for operators. Technical failure details remain in the audit event comment.
 */
public enum ProvisioningFailureCode {
    REQUESTER_MISSING,
    RESOURCE_MISSING,
    RESOURCE_TYPE_MISMATCH,
    REALM_MISMATCH,
    PROVIDER_UNAVAILABLE,
    UNEXPECTED_FAILURE,
    UNKNOWN;

    public static ProvisioningFailureCode fromStoredValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }
}
