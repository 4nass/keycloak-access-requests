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
    it("loads a paginated audit event page with date, type, actor, and request filters", async () => {
        const event = {
            id: "event-1",
            requestId: "request/1",
            type: "REQUEST_APPROVED",
            actorId: "approver-1",
            occurredAt: "2026-09-24T10:00:00Z"
        };
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            items: [event], page: 1, size: 10, total: 11
        }));
        const api = createApi(fetchMock) as unknown as {
            auditEvents(query: Record<string, string | number>): Promise<unknown>;
        };

        await expect(api.auditEvents({
            page: 1, size: 10, from: "2026-09-24T00:00:00Z", to: "2026-09-25T00:00:00Z",
            type: "REQUEST_APPROVED", actorId: "approver-1", requestId: "request/1"
        })).resolves.toEqual({ items: [event], page: 1, size: 10, total: 11 });
        const { url, init } = request(fetchMock);
        const parsed = new URL(url);
        expect(parsed.pathname).toBe("/realms/finance/access-requests/admin/events");
        expect(Object.fromEntries(parsed.searchParams)).toEqual({
            page: "1", size: "10", from: "2026-09-24T00:00:00Z", to: "2026-09-25T00:00:00Z",
            type: "REQUEST_APPROVED", actorId: "approver-1", requestId: "request/1"
        });
        expect(init.headers).toEqual(expect.objectContaining({ authorization: "Bearer admin-console-token" }));
    });

    it("preserves empty audit pages and presents authorization failures without server error details", async () => {
        const emptyFetch = vi.fn().mockResolvedValue(jsonResponse({ items: [], page: 0, size: 20, total: 0 }));
        const emptyApi = createApi(emptyFetch) as unknown as { auditEvents(): Promise<unknown> };
        await expect(emptyApi.auditEvents()).resolves.toEqual({ items: [], page: 0, size: 20, total: 0 });

        for (const status of [401, 403]) {
            const deniedFetch = vi.fn().mockResolvedValue(jsonResponse({
                code: "INTERNAL_FAILURE", message: "Sensitive details", requestId: "trace-1"
            }, status));
            const deniedApi = createApi(deniedFetch) as unknown as { auditEvents(): Promise<unknown> };
            await expect(deniedApi.auditEvents()).rejects.toMatchObject({ status, requestId: "trace-1" });
        }
    });

    it("opens the authorized administrative request detail linked from an audit event", async () => {
        const fetchMock = vi.fn().mockImplementation(async () => jsonResponse({
            id: "request/1", requesterId: "requester-1", history: [{ type: "REQUEST_CREATED" }]
        }));
        const api = createApi(fetchMock) as unknown as {
            auditRequest(id: string, query?: { page: number; size: number }): Promise<unknown>
        };

        await expect(api.auditRequest("request/1")).resolves.toMatchObject({ id: "request/1" });
        expect(request(fetchMock).url).toBe(
            "https://keycloak.example/realms/finance/access-requests/admin/requests/request%2F1"
                + "?historyPage=0&historySize=20"
        );
        await api.auditRequest("request/1", { page: 2, size: 10 });
        expect(String(fetchMock.mock.calls[1][0])).toContain("?historyPage=2&historySize=10");
    });

    it("reads the server-authoritative catalog capability with the administrator token", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            canManageCatalog: true,
            canManageNotifications: true,
            canManageProvisioningFailures: true
        }));

        await expect(createApi(fetchMock).capabilities()).resolves.toEqual({
            canManageCatalog: true,
            canManageNotifications: true,
            canManageProvisioningFailures: true
        });
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/capabilities",
            init: expect.objectContaining({
                headers: expect.objectContaining({ authorization: "Bearer admin-console-token" })
            })
        });
    });

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

    it("loads failed notification deliveries and their operational summary", async () => {
        const failedDelivery = {
            attemptCount: 10,
            entitlementId: "finance-reader",
            id: "delivery-1",
            lastAttemptAt: "2026-09-22T10:00:00Z",
            notificationType: "REQUEST_SUBMITTED" as const,
            recipientId: "user-1",
            recipientType: "USER" as const,
            requestId: "request-1"
        };
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(jsonResponse({
                discarded: 2,
                delivered: 8,
                failed: 1,
                pending: 3,
                processing: 1
            }))
            .mockResolvedValueOnce(jsonResponse({ items: [failedDelivery], page: 1, size: 10, total: 11 }));
        const api = createApi(fetchMock);

        await expect(api.notificationDeliverySummary()).resolves.toMatchObject({ failed: 1, pending: 3 });
        await expect(api.notificationDeliveries({ page: 1, size: 10 })).resolves.toEqual({
            items: [failedDelivery],
            page: 1,
            size: 10,
            total: 11
        });
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/notification-deliveries/summary",
            init: expect.objectContaining({
                headers: expect.objectContaining({ authorization: "Bearer admin-console-token" })
            })
        });
        const [listUrl] = fetchMock.mock.calls[1] as [RequestInfo | URL, RequestInit];
        expect(String(listUrl)).toBe(
            "https://keycloak.example/realms/finance/access-requests/admin/notification-deliveries?page=1&size=10"
        );
    });

    it("loads paginated failed provisioning requests with only operational response fields", async () => {
        const failedRequest = {
            decisionStatus: "APPROVED" as const,
            entitlementId: "finance-reader",
            id: "request-1",
            provisioningStatus: "FAILED" as const,
            failureCode: "RESOURCE_MISSING" as const,
            requesterId: "user-1",
            resourceName: "Finance Reader",
            resourceType: "CLIENT_ROLE" as const,
            updatedAt: "2026-09-22T10:00:00Z"
        };
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            items: [failedRequest],
            page: 1,
            size: 10,
            total: 11
        }));

        await expect(createApi(fetchMock).failedProvisioningRequests({ page: 1, size: 10 })).resolves.toEqual({
            items: [failedRequest],
            page: 1,
            size: 10,
            total: 11
        });
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/provisioning-failures?page=1&size=10&state=OPEN",
            init: expect.objectContaining({
                headers: expect.objectContaining({ authorization: "Bearer admin-console-token" })
            })
        });
        expect(String(fetchMock.mock.calls[0][0])).not.toContain("failureReason");
    });

    it("loads the realm-scoped closure archive without changing the active queue", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            items: [{ id: "request-1", closedAt: "2026-09-23T10:00:00Z",
                closedBy: "manager-1", closureReason: "The role was removed." }],
            page: 0, size: 10, total: 1
        }));

        await expect(createApi(fetchMock).failedProvisioningRequests({ page: 0, size: 10, state: "CLOSED" }))
            .resolves.toMatchObject({ total: 1, items: [{ closedBy: "manager-1" }] });
        expect(request(fetchMock).url).toBe(
            "https://keycloak.example/realms/finance/access-requests/admin/provisioning-failures?page=0&size=10&state=CLOSED"
        );
    });

    it("retries a failed provisioning request using the request-scoped admin endpoint", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            decisionStatus: "APPROVED",
            entitlementId: "finance-reader",
            id: "request-1",
            provisioningStatus: "SUCCEEDED"
        }));

        await expect(createApi(fetchMock).retryFailedProvisioning("request/1")).resolves.toMatchObject({
            decisionStatus: "APPROVED",
            provisioningStatus: "SUCCEEDED"
        });
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/requests/request%2F1/provisioning/retry",
            init: expect.objectContaining({
                headers: expect.objectContaining({ authorization: "Bearer admin-console-token" }),
                method: "POST"
            })
        });
    });

    it("closes an unrecoverable failure with a request-scoped reason", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            id: "request-1", decisionStatus: "APPROVED", provisioningStatus: "FAILED",
            closedAt: "2026-09-23T10:00:00Z", closedBy: "manager-1", reason: "User removed permanently."
        }));

        await expect(createApi(fetchMock).closeFailedProvisioning("request/1", "User removed permanently."))
            .resolves.toMatchObject({ id: "request-1", closedBy: "manager-1" });
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/requests/request%2F1/provisioning/close",
            init: expect.objectContaining({
                headers: expect.objectContaining({
                    authorization: "Bearer admin-console-token",
                    "content-type": "application/json"
                }),
                method: "POST",
                body: JSON.stringify({ reason: "User removed permanently." })
            })
        });
    });

    it.each([
        [401, "accessRequestsAdminErrorUnauthorized"],
        [403, "accessRequestsAdminErrorForbidden"],
        [409, "accessRequestsAdminErrorConflict"]
    ])("maps a %i failed-provisioning retry response to a safe translated error", async (status, messageKey) => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            code: "INTERNAL_PROVISIONING_DETAIL",
            message: "Sensitive provisioning internals must not reach the UI.",
            requestId: "request-1"
        }, status));

        try {
            await createApi(fetchMock).retryFailedProvisioning("request-1");
            throw new Error("Expected the failed provisioning retry to reject.");
        } catch (error) {
            expect(error).toMatchObject({ requestId: "request-1", status });
            expect(presentEntitlementsAdminError(error)).toEqual({ messageKey, requestId: "request-1" });
        }
    });

    it("requeues a failed notification delivery without expecting a JSON response body", async () => {
        const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));

        await expect(createApi(fetchMock).retryNotificationDelivery("delivery/1")).resolves.toBeUndefined();
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/notification-deliveries/delivery%2F1/retry",
            init: expect.objectContaining({ method: "POST" })
        });
    });

    it("loads Keycloak-managed references through the delegated catalog API", async () => {
        const reference = {
            description: "Reviews finance access requests",
            id: "role-finance-approvers",
            name: "finance-approvers",
            type: "REALM_ROLE" as const
        };
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ items: [reference] }));

        await expect(createApi(fetchMock).references("REALM_ROLE", { max: 25, search: "finance" }))
            .resolves.toEqual([reference]);
        expect(request(fetchMock)).toEqual({
            url: "https://keycloak.example/realms/finance/access-requests/admin/references?type=REALM_ROLE&search=finance&max=25",
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

    it("identifies unauthorized capability checks without exposing the response body", async () => {
        const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
            code: "INTERNAL_ADMIN_MESSAGE",
            message: "This detail must never be shown in the browser.",
            requestId: "request-42"
        }, 403));

        await expect(createApi(fetchMock).capabilities()).rejects.toMatchObject({
            requestId: "request-42",
            status: 403
        });
    });

    it("preserves a network failure for page-level recovery", async () => {
        const networkError = new TypeError("Failed to fetch");
        const fetchMock = vi.fn().mockRejectedValue(networkError);

        await expect(createApi(fetchMock).list()).rejects.toBe(networkError);
    });
});
