import type { ProvisioningFailureCode } from "../api/EntitlementsAdminApi";

export function failureCodeKey(code: ProvisioningFailureCode): string {
    const keys: Record<ProvisioningFailureCode, string> = {
        REQUESTER_MISSING: "accessRequestsAdminFailureRequesterMissing",
        RESOURCE_MISSING: "accessRequestsAdminFailureResourceMissing",
        RESOURCE_TYPE_MISMATCH: "accessRequestsAdminFailureResourceTypeMismatch",
        REALM_MISMATCH: "accessRequestsAdminFailureRealmMismatch",
        PROVIDER_UNAVAILABLE: "accessRequestsAdminFailureProviderUnavailable",
        UNEXPECTED_FAILURE: "accessRequestsAdminFailureUnexpected",
        UNKNOWN: "accessRequestsAdminFailureUnknown"
    };
    return keys[code] ?? keys.UNKNOWN;
}
