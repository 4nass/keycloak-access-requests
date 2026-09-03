export type AccessRequestsApiError = Error & {
    code: string;
    requestId?: string;
    status: number;
};

export type AccessRequestsErrorPresentation = {
    messageKey: AccessRequestsErrorMessageKey;
    requestId?: string;
};

type AccessRequestsErrorMessageKey =
    | "accessRequestsErrorConflict"
    | "accessRequestsErrorForbidden"
    | "accessRequestsErrorInvalidRequest"
    | "accessRequestsErrorNotFound"
    | "accessRequestsErrorUnauthorized"
    | "accessRequestsErrorUnavailable"
    | "accessRequestsErrorUnexpected";

export type Page<T> = {
    items: T[];
    page: number;
    size: number;
    total: number;
};

export type CatalogItem = {
    id: string;
    resourceType: string;
    displayName: string;
    description: string;
    riskLevel: string;
    alreadyGranted: boolean;
    pendingRequest: boolean;
};

export type RequestSummary = {
    id: string;
    entitlementId: string;
    resourceType: string;
    resourceName: string;
    decisionStatus: string;
    provisioningStatus: string;
    createdAt: string;
};

export type RequestDetails = RequestSummary & {
    justification: string;
    decision?: {
        approverId: string;
        comment: string | null;
        decidedAt: string;
    };
    history: Array<{
        type: string;
        occurredAt: string;
    }>;
};

export type PendingRequest = {
    id: string;
    requesterId: string;
    entitlementId: string;
    resourceType: string;
    resourceName: string;
    riskLevel: string;
    justification: string;
    createdAt: string;
};

export type AccessRequestCapabilities = {
    canApprove: boolean;
};

export type AccessRequestsApi = {
    catalog(query?: { page?: number; size?: number; search?: string }): Promise<Page<CatalogItem>>;
    submitRequest(submission: { entitlementId: string; justification: string }): Promise<{
        id: string;
        entitlementId: string;
        decisionStatus: string;
        provisioningStatus: string;
    }>;
    mine(query?: { page?: number; size?: number }): Promise<Page<RequestSummary>>;
    requestDetails(requestId: string): Promise<RequestDetails>;
    pending(query?: { page?: number; size?: number }): Promise<Page<PendingRequest>>;
    capabilities(): Promise<AccessRequestCapabilities>;
    cancel(requestId: string): Promise<void>;
    approve(requestId: string, decision: { comment: string }): Promise<void>;
    reject(requestId: string, decision: { comment: string }): Promise<void>;
};

type ErrorResponse = {
    code?: string;
    requestId?: string;
};

type KeycloakCatalogItem = Omit<CatalogItem, "displayName" | "resourceType"> & {
    displayName?: string;
    name?: string;
    resourceType?: string;
    type?: string;
};

type Options = {
    serverBaseUrl: string;
    realm: string;
    getAccessToken: () => Promise<string>;
    fetch: typeof fetch;
};

export function createAccessRequestsApi({ serverBaseUrl, realm, getAccessToken, fetch: fetchRequest }: Options): AccessRequestsApi {
    const endpoint = (path: string) =>
        `${serverBaseUrl.replace(/\/$/, "")}/realms/${encodeURIComponent(realm)}/access-requests/${path}`;

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

    const pageQuery = (query: { page?: number; size?: number; search?: string } = {}) => {
        const parameters = new URLSearchParams({
            page: String(query.page ?? 0),
            size: String(query.size ?? 20)
        });
        if (query.search) {
            parameters.set("search", query.search);
        }
        return parameters.toString();
    };

    return {
        async catalog(query) {
            const page = await request<Page<KeycloakCatalogItem>>(`catalog?${pageQuery(query)}`);
            return {
                ...page,
                items: page.items.map((item) => ({
                    ...item,
                    displayName: item.displayName ?? item.name ?? "",
                    resourceType: item.resourceType ?? item.type ?? ""
                }))
            };
        },
        submitRequest: (submission) =>
            request("requests", {
                body: JSON.stringify(submission),
                headers: { "content-type": "application/json" },
                method: "POST"
            }),
        mine: (query) => request(`mine?${pageQuery(query)}`),
        requestDetails: (requestId) => request(`mine/${encodeURIComponent(requestId)}`),
        pending: (query) => request(`pending?${pageQuery(query)}`),
        capabilities: () => request("capabilities"),
        async cancel(requestId) {
            await request<void>(`${encodeURIComponent(requestId)}/cancel`, { method: "POST" });
        },
        approve: (requestId, decision) => decide(requestId, "approve", decision),
        reject: (requestId, decision) => decide(requestId, "reject", decision)
    };

    function decide(requestId: string, decision: "approve" | "reject", body: { comment: string }) {
        return request<void>(`${encodeURIComponent(requestId)}/${decision}`, {
            body: JSON.stringify(body),
            headers: { "content-type": "application/json" },
            method: "POST"
        });
    }
}

async function apiError(response: Response): Promise<AccessRequestsApiError> {
    const body = await response.json().catch(() => ({} as ErrorResponse)) as ErrorResponse;
    const error = new Error("The access request API call failed.") as AccessRequestsApiError;
    error.code = body.code ?? `HTTP_${response.status}`;
    error.requestId = body.requestId;
    error.status = response.status;
    return error;
}

export function presentAccessRequestsError(error: unknown): AccessRequestsErrorPresentation {
    if (!isAccessRequestsApiError(error)) {
        return { messageKey: "accessRequestsErrorUnexpected" };
    }

    return {
        messageKey: errorMessageKey(error),
        requestId: error.requestId
    };
}

function isAccessRequestsApiError(error: unknown): error is AccessRequestsApiError {
    return error instanceof Error
        && typeof (error as Partial<AccessRequestsApiError>).code === "string"
        && typeof (error as Partial<AccessRequestsApiError>).status === "number";
}

function errorMessageKey(error: AccessRequestsApiError): AccessRequestsErrorMessageKey {
    const codeMessageKey = errorCodeMessageKeys[error.code];
    if (codeMessageKey) {
        return codeMessageKey;
    }

    if (error.status === 400 || error.status === 422) {
        return "accessRequestsErrorInvalidRequest";
    }
    if (error.status === 401) {
        return "accessRequestsErrorUnauthorized";
    }
    if (error.status === 403) {
        return "accessRequestsErrorForbidden";
    }
    if (error.status === 404) {
        return "accessRequestsErrorNotFound";
    }
    if (error.status === 409) {
        return "accessRequestsErrorConflict";
    }
    if (error.status >= 500) {
        return "accessRequestsErrorUnavailable";
    }
    return "accessRequestsErrorUnexpected";
}

const errorCodeMessageKeys: Record<string, AccessRequestsErrorMessageKey> = {
    CONCURRENT_ENTITLEMENT_MODIFICATION: "accessRequestsErrorConflict",
    CONCURRENT_MODIFICATION: "accessRequestsErrorConflict",
    ENTITLEMENT_ALREADY_EXISTS: "accessRequestsErrorConflict",
    ENTITLEMENT_NOT_REQUESTABLE: "accessRequestsErrorConflict",
    INVALID_REQUEST_STATE: "accessRequestsErrorConflict",
    INVALID_DECISION_SUBMISSION: "accessRequestsErrorInvalidRequest",
    INVALID_ENTITLEMENT_QUERY: "accessRequestsErrorInvalidRequest",
    INVALID_REQUEST_QUERY: "accessRequestsErrorInvalidRequest",
    ENTITLEMENT_NOT_FOUND: "accessRequestsErrorNotFound",
    REQUEST_NOT_FOUND: "accessRequestsErrorNotFound",
    NOT_AUTHORIZED_APPROVER: "accessRequestsErrorForbidden",
    REQUEST_CANCELLATION_FORBIDDEN: "accessRequestsErrorForbidden",
    SELF_APPROVAL_FORBIDDEN: "accessRequestsErrorForbidden"
};
