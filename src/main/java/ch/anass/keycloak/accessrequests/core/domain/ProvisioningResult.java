package ch.anass.keycloak.accessrequests.core.domain;

import java.util.Objects;

/**
 * The outcome of assigning an entitlement to a requester.
 */
public record ProvisioningResult(ProvisioningStatus status, String failureReason) {

    public ProvisioningResult {
        status = Objects.requireNonNull(status, "status must not be null");
        if (status == ProvisioningStatus.NOT_STARTED) {
            throw new IllegalArgumentException("A provisioning result must be final");
        }
        if (status == ProvisioningStatus.SUCCEEDED && failureReason != null) {
            throw new IllegalArgumentException("A successful provisioning result must not have a failure reason");
        }
        if (status == ProvisioningStatus.FAILED) {
            failureReason = requireText(failureReason, "failureReason");
        }
    }

    public static ProvisioningResult succeeded() {
        return new ProvisioningResult(ProvisioningStatus.SUCCEEDED, null);
    }

    public static ProvisioningResult failed(String failureReason) {
        return new ProvisioningResult(ProvisioningStatus.FAILED, failureReason);
    }

    public boolean isSuccessful() {
        return status == ProvisioningStatus.SUCCEEDED;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
