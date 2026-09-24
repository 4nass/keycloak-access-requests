import type { AdminAuditEventType } from "../api/EntitlementsAdminApi";

export const auditEventTypes: Record<AdminAuditEventType, string> = {
    REQUEST_CREATED: "accessRequestsAdminEventRequestCreated",
    REQUEST_CANCELED: "accessRequestsAdminEventRequestCanceled",
    REQUEST_APPROVED: "accessRequestsAdminEventRequestApproved",
    REQUEST_REJECTED: "accessRequestsAdminEventRequestRejected",
    PROVISIONING_STARTED: "accessRequestsAdminEventProvisioningStarted",
    PROVISIONING_SUCCEEDED: "accessRequestsAdminEventProvisioningSucceeded",
    PROVISIONING_FAILED: "accessRequestsAdminEventProvisioningFailed",
    PROVISIONING_CLOSED: "accessRequestsAdminEventProvisioningClosed"
};
