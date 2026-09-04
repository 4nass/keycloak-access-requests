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

export type EntitlementCreation = Pick<
    Entitlement,
    "resourceType" | "resourceId" | "displayName" | "description" | "riskLevel" | "approverRoleId"
>;

export type EntitlementUpdate = Pick<
    Entitlement,
    "displayName" | "description" | "riskLevel" | "approverRoleId" | "requestable" | "version"
>;

export type EntitlementsAdminApi = {
    list(query?: { page?: number; size?: number }): Promise<EntitlementPage>;
    create(submission: EntitlementCreation): Promise<Entitlement>;
    update(id: string, submission: EntitlementUpdate): Promise<Entitlement>;
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
        `${serverBaseUrl.replace(/\/$/, "")}/realms/${encodeURIComponent(realm)}/access-requests/admin/entitlements${path}`;

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

        return response.json() as Promise<T>;
    };

    const pageQuery = (query: { page?: number; size?: number } = {}) => new URLSearchParams({
        page: String(query.page ?? 0),
        size: String(query.size ?? 20)
    });

    return {
        list: (query) => request(`?${pageQuery(query)}`),
        create: (submission) => request("", json("POST", submission)),
        update: (id, submission) => request(`/${encodeURIComponent(id)}`, json("PUT", submission))
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
    if (!isEntitlementsAdminApiError(error)) {
        return { messageKey: "accessRequestsAdminErrorUnexpected" };
    }

    return {
        messageKey: errorMessageKey(error),
        requestId: error.requestId
    };
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
