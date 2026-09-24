export type Entitlement = {
    id: string;
    resourceType: "REALM_ROLE" | "CLIENT_ROLE" | "GROUP";
    resourceId: string;
    displayName: string;
    description: string;
    riskLevel: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
    approverRoleId: string;
    requestable: boolean;
    createdAt: string;
    updatedAt: string;
    version: number;
};

export type EntitlementPage = {
    items: Entitlement[];
    page: number;
    size: number;
    total: number;
};

export type AdminAuditEventType =
    | "REQUEST_CREATED" | "REQUEST_CANCELED" | "REQUEST_APPROVED" | "REQUEST_REJECTED"
    | "PROVISIONING_STARTED" | "PROVISIONING_SUCCEEDED" | "PROVISIONING_FAILED"
    | "PROVISIONING_CLOSED";

export type AdminAuditEvent = {
    id: string;
    requestId: string;
    type: AdminAuditEventType;
    actorId: string;
    occurredAt: string;
};

export type AdminAuditEventPage = {
    items: AdminAuditEvent[];
    page: number;
    size: number;
    total: number;
};

export type AdminAuditEventQuery = {
    from?: string;
    to?: string;
    type?: AdminAuditEventType;
    actorId?: string;
    requestId?: string;
    page?: number;
    size?: number;
};

export type AdminAuditRequestDetails = {
    id: string;
    requesterId: string;
    entitlementId: string;
    resourceType: Entitlement["resourceType"];
    resourceName: string;
    decisionStatus: "PENDING" | "APPROVED" | "REJECTED" | "CANCELED";
    provisioningStatus: "NOT_STARTED" | "SUCCEEDED" | "FAILED";
    createdAt: string;
    provisioningClosedAt: string | null;
    justification: string;
    decision: { approverId: string; comment: string; decidedAt: string } | null;
    history: { type: AdminAuditEventType; actorId: string; occurredAt: string }[];
};

export type EntitlementCreation = Pick<
    Entitlement,
    "resourceType" | "resourceId" | "displayName" | "description" | "riskLevel" | "approverRoleId"
>;

export type EntitlementUpdate = Pick<
    Entitlement,
    "displayName" | "description" | "riskLevel" | "approverRoleId" | "requestable" | "version"
>;

export type AdminCapabilities = {
    canManageCatalog: boolean;
    canManageNotifications: boolean;
    canManageProvisioningFailures: boolean;
};

export type FailedProvisioningRequest = {
    id: string;
    requesterId: string;
    entitlementId: string;
    resourceType: Entitlement["resourceType"];
    resourceName: string;
    decisionStatus: "APPROVED";
    provisioningStatus: "FAILED";
    failureCode: ProvisioningFailureCode;
    updatedAt: string;
    closedAt: string | null;
    closedBy: string | null;
    closureReason: string | null;
};

export type ProvisioningFailureCode =
    | "REQUESTER_MISSING"
    | "RESOURCE_MISSING"
    | "RESOURCE_TYPE_MISMATCH"
    | "REALM_MISMATCH"
    | "PROVIDER_UNAVAILABLE"
    | "UNEXPECTED_FAILURE"
    | "UNKNOWN";

export type FailedProvisioningRequestPage = {
    items: FailedProvisioningRequest[];
    page: number;
    size: number;
    total: number;
};

export type ProvisioningRetryResult = {
    id: string;
    entitlementId: string;
    decisionStatus: "APPROVED";
    provisioningStatus: "SUCCEEDED" | "FAILED";
};

export type ProvisioningClosureResult = {
    id: string;
    decisionStatus: "APPROVED";
    provisioningStatus: "FAILED";
    closedAt: string;
    closedBy: string;
    reason: string;
};

export type NotificationDelivery = {
    id: string;
    requestId: string;
    entitlementId: string;
    recipientId: string;
    recipientType: "USER" | "REALM_ROLE";
    notificationType: "REQUEST_SUBMITTED" | "REQUEST_APPROVED" | "REQUEST_REJECTED" | "PROVISIONING_FAILED" | "PROVISIONING_CLOSED";
    attemptCount: number;
    lastAttemptAt?: string;
};

export type NotificationDeliveryPage = {
    items: NotificationDelivery[];
    page: number;
    size: number;
    total: number;
};

export type NotificationDeliverySummary = {
    pending: number;
    processing: number;
    delivered: number;
    discarded: number;
    failed: number;
};

export type KeycloakReference = {
    type: Entitlement["resourceType"];
    id: string;
    name: string;
    description: string;
};

export type EntitlementsAdminApi = {
    capabilities(): Promise<AdminCapabilities>;
    list(query?: { page?: number; size?: number }): Promise<EntitlementPage>;
    auditEvents(query?: AdminAuditEventQuery): Promise<AdminAuditEventPage>;
    auditRequest(id: string): Promise<AdminAuditRequestDetails>;
    notificationDeliveries(query?: { page?: number; size?: number }): Promise<NotificationDeliveryPage>;
    notificationDeliverySummary(): Promise<NotificationDeliverySummary>;
    failedProvisioningRequests(query?: { page?: number; size?: number; state?: "OPEN" | "CLOSED" }): Promise<FailedProvisioningRequestPage>;
    retryFailedProvisioning(id: string): Promise<ProvisioningRetryResult>;
    closeFailedProvisioning(id: string, reason: string): Promise<ProvisioningClosureResult>;
    references(type: Entitlement["resourceType"], query?: { search?: string; max?: number }): Promise<KeycloakReference[]>;
    create(submission: EntitlementCreation): Promise<Entitlement>;
    update(id: string, submission: EntitlementUpdate): Promise<Entitlement>;
    retryNotificationDelivery(id: string): Promise<void>;
};

export type EntitlementsAdminApiError = Error & {
    code: string;
    requestId?: string;
    status: number;
};

export type EntitlementsAdminErrorPresentation = {
    messageKey: EntitlementsAdminErrorMessageKey;
    requestId?: string;
};

type EntitlementsAdminErrorMessageKey =
    | "accessRequestsAdminErrorConflict"
    | "accessRequestsAdminErrorForbidden"
    | "accessRequestsAdminErrorInvalidRequest"
    | "accessRequestsAdminErrorNotFound"
    | "accessRequestsAdminErrorUnauthorized"
    | "accessRequestsAdminErrorUnavailable"
    | "accessRequestsAdminErrorUnexpected";

type ErrorResponse = {
    code?: string;
    requestId?: string;
};

type Options = {
    serverBaseUrl: string;
    realm: string;
    getAccessToken: () => Promise<string>;
    fetch: typeof fetch;
};

export function createEntitlementsAdminApi({ serverBaseUrl, realm, getAccessToken, fetch: fetchRequest }: Options): EntitlementsAdminApi {
    const endpoint = (path: string) =>
        `${serverBaseUrl.replace(/\/$/, "")}/realms/${encodeURIComponent(realm)}/access-requests${path}`;

    const request = async <T>(path: string, init: RequestInit = {}): Promise<T> => {
        const accessToken = await getAccessToken();
        const response = await fetchRequest(endpoint(path), {
            ...init,
            headers: {
                accept: "application/json",
                authorization: `Bearer ${accessToken}`,
                ...init.headers
            }
        });

        if (!response.ok) {
            throw await apiError(response);
        }

        if (response.status === 204) {
            return undefined as T;
        }
        return response.json() as Promise<T>;
    };

    const pageQuery = (query: { page?: number; size?: number } = {}) => new URLSearchParams({
        page: String(query.page ?? 0),
        size: String(query.size ?? 20)
    });
    const auditQuery = (query: AdminAuditEventQuery = {}) => {
        const parameters = pageQuery(query);
        for (const key of ["from", "to", "type", "actorId", "requestId"] as const) {
            if (query[key]) {
                parameters.set(key, query[key]);
            }
        }
        return parameters;
    };
    const referenceQuery = (type: Entitlement["resourceType"], query: { search?: string; max?: number } = {}) => new URLSearchParams({
        type,
        search: query.search ?? "",
        max: String(query.max ?? 50)
    });

    return {
        capabilities: () => request("/admin/capabilities"),
        list: (query) => request(`/admin/entitlements?${pageQuery(query)}`),
        auditEvents: (query) => request(`/admin/events?${auditQuery(query)}`),
        auditRequest: (id) => request(`/admin/requests/${encodeURIComponent(id)}`),
        notificationDeliveries: (query) => request(`/admin/notification-deliveries?${pageQuery(query)}`),
        notificationDeliverySummary: () => request("/admin/notification-deliveries/summary"),
        failedProvisioningRequests: (query) => request(
            `/admin/provisioning-failures?${pageQuery(query)}&state=${query?.state ?? "OPEN"}`
        ),
        retryFailedProvisioning: (id) => request(
            `/admin/requests/${encodeURIComponent(id)}/provisioning/retry`,
            { method: "POST" }
        ),
        closeFailedProvisioning: (id, reason) => request(
            `/admin/requests/${encodeURIComponent(id)}/provisioning/close`,
            json("POST", { reason })
        ),
        references: async (type, query) => {
            const response = await request<{ items: KeycloakReference[] }>(`/admin/references?${referenceQuery(type, query)}`);
            return response.items;
        },
        create: (submission) => request("/admin/entitlements", json("POST", submission)),
        update: (id, submission) => request(`/admin/entitlements/${encodeURIComponent(id)}`, json("PUT", submission)),
        retryNotificationDelivery: (id) => request(
            `/admin/notification-deliveries/${encodeURIComponent(id)}/retry`,
            { method: "POST" }
        )
    };
}

function json(method: "POST" | "PUT", body: unknown): RequestInit {
    return {
        body: JSON.stringify(body),
        headers: { "content-type": "application/json" },
        method
    };
}

async function apiError(response: Response): Promise<EntitlementsAdminApiError> {
    const body = await response.json().catch(() => ({} as ErrorResponse)) as ErrorResponse;
    const error = new Error("The access request API call failed.") as EntitlementsAdminApiError;
    error.code = body.code ?? `HTTP_${response.status}`;
    error.requestId = body.requestId;
    error.status = response.status;
    return error;
}

export function presentEntitlementsAdminError(error: unknown): EntitlementsAdminErrorPresentation {
    if (error instanceof TypeError) {
        return { messageKey: "accessRequestsAdminErrorUnavailable" };
    }
    if (!isEntitlementsAdminApiError(error)) {
        return { messageKey: "accessRequestsAdminErrorUnexpected" };
    }

    return {
        messageKey: errorMessageKey(error),
        requestId: error.requestId
    };
}

export function isEntitlementsAdminAuthorizationError(error: unknown): boolean {
    return isEntitlementsAdminApiError(error) && (error.status === 401 || error.status === 403);
}

function isEntitlementsAdminApiError(error: unknown): error is EntitlementsAdminApiError {
    return error instanceof Error
        && typeof (error as Partial<EntitlementsAdminApiError>).code === "string"
        && typeof (error as Partial<EntitlementsAdminApiError>).status === "number";
}

function errorMessageKey(error: EntitlementsAdminApiError): EntitlementsAdminErrorMessageKey {
    if (error.status === 400 || error.status === 422) {
        return "accessRequestsAdminErrorInvalidRequest";
    }
    if (error.status === 401) {
        return "accessRequestsAdminErrorUnauthorized";
    }
    if (error.status === 403) {
        return "accessRequestsAdminErrorForbidden";
    }
    if (error.status === 404) {
        return "accessRequestsAdminErrorNotFound";
    }
    if (error.status === 409) {
        return "accessRequestsAdminErrorConflict";
    }
    if (error.status >= 500) {
        return "accessRequestsAdminErrorUnavailable";
    }
    return "accessRequestsAdminErrorUnexpected";
}
