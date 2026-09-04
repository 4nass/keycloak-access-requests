import { describe, expect, it, vi } from "vitest";

import {
    createEntitlementsAdminApi,
    presentEntitlementsAdminError,
    type EntitlementsAdminApi
} from "./EntitlementsAdminApi";

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        headers: { "content-type": "application/json" },
        status
    });
}

function createApi(fetchMock: ReturnType<typeof vi.fn>): EntitlementsAdminApi {
    return createEntitlementsAdminApi({
        fetch: fetchMock as unknown as typeof fetch,
        getAccessToken: vi.fn().mockResolvedValue("admin-console-token"),
        realm: "finance",
        serverBaseUrl: "https://keycloak.example"
    });
}

function request(fetchMock: ReturnType<typeof vi.fn>) {
    const [url, init] = fetchMock.mock.calls[0] as [RequestInfo | URL, RequestInit];
    return { init, url: String(url) };
}

const entitlement = {
    approverRoleId: "role-finance-approvers",
    createdAt: "2026-09-04T10:00:00Z",
    description: "Read-only finance access",
    displayName: "Finance Reader",
    id: "finance-reader",
    requestable: true,
    resourceId: "finance-reader-role",
    resourceType: "CLIENT_ROLE" as const,
    riskLevel: "LOW" as const,
    updatedAt: "2026-09-04T10:00:00Z",
    version: 4
};

describe("Entitlements administration API client", () => {
    it("loads the complete catalog page with the administrator token", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            items: [entitlement],
            page: 1,
            size: 10,
            total: 11
        }));

        await expect(createApi(fetchMock).list({ page: 1, size: 10 })).resolves.toEqual({
            items: [entitlement],
            page: 1,
            size: 10,
            total: 11
        });
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/entitlements?page=1&size=10",
            init: expect.objectContaining({
                headers: expect.objectContaining({ authorization: "Bearer admin-console-token" })
            })
        });
    });

    it("creates an entitlement using the immutable Keycloak resource fields", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse(entitlement, 201));
        const submission = {
            approverRoleId: entitlement.approverRoleId,
            description: entitlement.description,
            displayName: entitlement.displayName,
            resourceId: entitlement.resourceId,
            resourceType: entitlement.resourceType,
            riskLevel: entitlement.riskLevel
        };

        await expect(createApi(fetchMock).create(submission)).resolves.toEqual(entitlement);
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/entitlements",
            init: expect.objectContaining({ body: JSON.stringify(submission), method: "POST" })
        });
    });

    it("updates metadata, requestability, and the optimistic lock version", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ...entitlement, requestable: false, version: 5 }));
        const submission = {
            approverRoleId: entitlement.approverRoleId,
            description: entitlement.description,
            displayName: entitlement.displayName,
            requestable: false,
            riskLevel: entitlement.riskLevel,
            version: entitlement.version
        };

        await expect(createApi(fetchMock).update("finance/reader", submission)).resolves.toMatchObject({
            requestable: false,
            version: 5
        });
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/entitlements/finance%2Freader",
            init: expect.objectContaining({ body: JSON.stringify(submission), method: "PUT" })
        });
    });

    it.each([
        [401, "accessRequestsAdminErrorUnauthorized"],
        [403, "accessRequestsAdminErrorForbidden"],
        [404, "accessRequestsAdminErrorNotFound"],
        [409, "accessRequestsAdminErrorConflict"],
        [503, "accessRequestsAdminErrorUnavailable"]
    ])("maps a %i response to a safe translated message", async (status, messageKey) => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            code: "INTERNAL_ADMIN_MESSAGE",
            message: "This detail must never be shown in the browser.",
            requestId: "request-42"
        }, status));

        try {
            await createApi(fetchMock).list();
        } catch (error) {
            expect(error).toMatchObject({
                message: "The access request API call failed.",
                requestId: "request-42",
                status
            });
            expect(presentEntitlementsAdminError(error)).toEqual({ messageKey, requestId: "request-42" });
        }
    });

    it("preserves a network failure for page-level recovery", async () => {
        const networkError = new TypeError("Failed to fetch");
        const fetchMock = vi.fn().mockRejectedValue(networkError);

        await expect(createApi(fetchMock).list()).rejects.toBe(networkError);
    });
});
