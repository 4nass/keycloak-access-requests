import { describe, expect, it, vi } from "vitest";

type ErrorResponse = {
    code: string;
    message: string;
    requestId?: string;
};

type Page<T> = {
    items: T[];
    page: number;
    size: number;
    total: number;
};

type CatalogItem = {
    id: string;
    resourceType: string;
    displayName: string;
    description: string;
    riskLevel: string;
    alreadyGranted: boolean;
    pendingRequest: boolean;
};

type RequestSummary = {
    id: string;
    entitlementId: string;
    resourceType: string;
    resourceName: string;
    decisionStatus: string;
    provisioningStatus: string;
    createdAt: string;
};

type RequestDetails = RequestSummary & {
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

type PendingRequest = {
    id: string;
    requesterId: string;
    entitlementId: string;
    resourceType: string;
    resourceName: string;
    riskLevel: string;
    justification: string;
    createdAt: string;
};

type AccessRequestsApi = {
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
    capabilities(): Promise<{ canApprove: boolean }>;
    cancel(requestId: string): Promise<void>;
    approve(requestId: string, decision: { comment: string }): Promise<void>;
    reject(requestId: string, decision: { comment: string }): Promise<void>;
};

type AccessRequestsApiModule = {
    createAccessRequestsApi(options: {
        serverBaseUrl: string;
        realm: string;
        getAccessToken: () => Promise<string>;
        fetch: typeof fetch;
    }): AccessRequestsApi;
};

const apiModulePath = "./AccessRequestsApi";

async function createApi(fetchMock: ReturnType<typeof vi.fn>): Promise<AccessRequestsApi> {
    const apiModule = (await import(/* @vite-ignore */ apiModulePath)) as AccessRequestsApiModule;
    return apiModule.createAccessRequestsApi({
        serverBaseUrl: "https://keycloak.example",
        realm: "finance",
        getAccessToken: vi.fn().mockResolvedValue("account-console-token"),
        fetch: fetchMock as unknown as typeof fetch
    });
}

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        headers: { "content-type": "application/json" },
        status
    });
}

function responseRequest(fetchMock: ReturnType<typeof vi.fn>) {
    const [request, options] = fetchMock.mock.calls[0] as [RequestInfo | URL, RequestInit];
    return { options, url: String(request) };
}

describe("Access Requests realm API client", () => {
    it("loads the requestable catalog with its query and bearer token", async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                items: [
                    {
                        id: "finance-reader",
                        type: "CLIENT_ROLE",
                        name: "Finance Reader",
                        description: "Read-only finance access",
                        riskLevel: "LOW",
                        alreadyGranted: false,
                        pendingRequest: false
                    }
                ],
                page: 1,
                size: 10,
                total: 11
            })
        );

        const catalog = await (await createApi(fetchMock)).catalog({ page: 1, size: 10, search: "finance" });

        expect(catalog).toMatchObject({ page: 1, size: 10, total: 11 });
        expect(catalog.items[0]).toMatchObject({ id: "finance-reader", displayName: "Finance Reader" });
        expect(responseRequest(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/catalog?page=1&size=10&search=finance",
            options: expect.objectContaining({
                headers: expect.objectContaining({ authorization: "Bearer account-console-token" })
            })
        });
    });

    it("submits a request as JSON and returns the created request", async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse(
                {
                    id: "request-1",
                    entitlementId: "finance-reader",
                    decisionStatus: "PENDING",
                    provisioningStatus: "NOT_STARTED"
                },
                201
            )
        );

        const request = await (await createApi(fetchMock)).submitRequest({
            entitlementId: "finance-reader",
            justification: "I need month-end reports."
        });

        expect(request).toMatchObject({ id: "request-1", decisionStatus: "PENDING" });
        expect(responseRequest(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/requests",
            options: expect.objectContaining({
                body: JSON.stringify({
                    entitlementId: "finance-reader",
                    justification: "I need month-end reports."
                }),
                method: "POST"
            })
        });
    });

    it("returns empty pages for the catalog, requester, and approver queues", async () => {
        const fetchMock = vi
            .fn()
            .mockResolvedValueOnce(jsonResponse({ items: [], page: 0, size: 20, total: 0 }))
            .mockResolvedValueOnce(jsonResponse({ items: [], page: 0, size: 20, total: 0 }))
            .mockResolvedValueOnce(jsonResponse({ items: [], page: 0, size: 20, total: 0 }));
        const api = await createApi(fetchMock);

        await expect(api.catalog()).resolves.toEqual({ items: [], page: 0, size: 20, total: 0 });
        await expect(api.mine()).resolves.toEqual({ items: [], page: 0, size: 20, total: 0 });
        await expect(api.pending()).resolves.toEqual({ items: [], page: 0, size: 20, total: 0 });
        expect(fetchMock.mock.calls.map(([request]) => String(request))).toEqual([
            "https://keycloak.example/realms/finance/access-requests/catalog?page=0&size=20",
            "https://keycloak.example/realms/finance/access-requests/mine?page=0&size=20",
            "https://keycloak.example/realms/finance/access-requests/pending?page=0&size=20"
        ]);
    });

    it("loads requester-visible request details using an encoded request identifier", async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                id: "request/1",
                entitlementId: "finance-reader",
                resourceType: "CLIENT_ROLE",
                resourceName: "Finance Reader",
                decisionStatus: "APPROVED",
                provisioningStatus: "SUCCEEDED",
                createdAt: "2026-09-03T10:00:00Z",
                justification: "I need month-end reports.",
                decision: {
                    approverId: "finance-approver",
                    comment: "Approved.",
                    decidedAt: "2026-09-03T10:05:00Z"
                },
                history: [{ type: "REQUEST_CREATED", occurredAt: "2026-09-03T10:00:00Z" }]
            })
        );

        const details = await (await createApi(fetchMock)).requestDetails("request/1");

        expect(details).toMatchObject({
            justification: "I need month-end reports.",
            history: [{ type: "REQUEST_CREATED" }]
        });
        expect(responseRequest(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/mine/request%2F1",
            options: expect.objectContaining({
                headers: expect.objectContaining({ authorization: "Bearer account-console-token" })
            })
        });
    });

    it("loads the approver capability without deriving it from pending requests", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ canApprove: true }));

        await expect((await createApi(fetchMock)).capabilities()).resolves.toEqual({ canApprove: true });
        expect(responseRequest(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/capabilities",
            options: expect.objectContaining({
                headers: expect.objectContaining({ authorization: "Bearer account-console-token" })
            })
        });
    });

    it("cancels only through the request cancellation endpoint", async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));

        await expect((await createApi(fetchMock)).cancel("request-1")).resolves.toBeUndefined();
        expect(responseRequest(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/request-1/cancel",
            options: expect.objectContaining({ method: "POST" })
        });
    });

    it.each([
        ["approve", "approve"],
        ["reject", "reject"]
    ])("sends a decision to the %s endpoint", async (method, endpoint) => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                id: "request-1",
                entitlementId: "finance-reader",
                decisionStatus: method === "approve" ? "APPROVED" : "REJECTED",
                provisioningStatus: "NOT_STARTED"
            })
        );
        const api = await createApi(fetchMock);

        await api[method as "approve" | "reject"]("request-1", { comment: "Reviewed by Finance." });

        expect(responseRequest(fetchMock)).toEqual({
            url: `https://keycloak.example/realms/finance/access-requests/request-1/${endpoint}`,
            options: expect.objectContaining({
                body: JSON.stringify({ comment: "Reviewed by Finance." }),
                method: "POST"
            })
        });
    });

    it.each([
        [401, "UNAUTHORIZED"],
        [403, "NOT_AUTHORIZED_APPROVER"],
        [409, "CONCURRENT_MODIFICATION"]
    ])("normalizes a %i response into a typed API error", async (status, code) => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse(
                {
                    code,
                    message: "The operation cannot be completed.",
                    requestId: "request-1"
                } satisfies ErrorResponse,
                status
            )
        );

        await expect((await createApi(fetchMock)).cancel("request-1")).rejects.toMatchObject({
            code,
            message: "The access request API call failed.",
            requestId: "request-1",
            status
        });
    });

    it("preserves network failures for the page-level alert handling", async () => {
        const networkError = new TypeError("Failed to fetch");
        const fetchMock = vi.fn().mockRejectedValue(networkError);

        await expect((await createApi(fetchMock)).catalog()).rejects.toBe(networkError);
    });
});
