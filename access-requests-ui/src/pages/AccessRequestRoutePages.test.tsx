import { createInstance } from "i18next";
import { render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
    api: {
        approve: vi.fn(),
        cancel: vi.fn(),
        catalog: vi.fn(),
        mine: vi.fn(),
        pending: vi.fn(),
        requestDetails: vi.fn(),
        reject: vi.fn(),
        submitRequest: vi.fn()
    },
    createAccessRequestsApi: vi.fn(),
    keycloak: {
        token: "account-console-token",
        updateToken: vi.fn()
    }
}));

vi.mock("@keycloak/keycloak-account-ui", () => ({
    useEnvironment: () => ({
        environment: {
            realm: "finance",
            serverBaseUrl: "https://keycloak.example"
        },
        keycloak: mocks.keycloak
    })
}));

vi.mock("../api/AccessRequestsApi", () => ({
    createAccessRequestsApi: mocks.createAccessRequestsApi
}));

import { ApprovalsRoutePage, MyRequestsRoutePage, RequestAccessRoutePage } from "./AccessRequestRoutePages";

const i18n = createInstance();

await i18n.init({
    initImmediate: false,
    lng: "en",
    resources: {
        en: {
            translation: {
                accessRequestsAlreadyGranted: "Already granted",
                accessRequestsApprove: "Approve",
                accessRequestsApproveEntitlement: "Approve {{entitlement}}",
                accessRequestsApprovals: "Approvals",
                accessRequestsCancel: "Cancel",
                accessRequestsCancelRequest: "Cancel request",
                accessRequestsConfirmApproval: "Confirm approval",
                accessRequestsDecisionComment: "Decision comment",
                accessRequestsJustification: "Justification",
                accessRequestsLoadError: "Unable to load access requests.",
                accessRequestsLoading: "Loading access requests",
                accessRequestsMyRequests: "My Requests",
                accessRequestsPending: "Pending",
                accessRequestsRequestAccess: "Request access",
                accessRequestsRequestAccessTo: "Request access to {{entitlement}}",
                accessRequestsRequestDetails: "{{entitlement}} request details",
                accessRequestsRequestPending: "Request pending",
                accessRequestsRequestedBy: "{{entitlement}} requested by {{requester}}",
                accessRequestsResourceType: "Resource type",
                accessRequestsRetry: "Retry",
                accessRequestsRisk: "Risk: {{riskLevel}}",
                accessRequestsRiskLabel: "Risk",
                accessRequestsSubmitRequest: "Submit request",
                accessRequestsViewDetails: "View details"
            }
        }
    }
});

function renderRoutePage(page: ReactNode) {
    return render(<I18nextProvider i18n={i18n}>{page}</I18nextProvider>);
}

function page<T>(items: T[]) {
    return { items, page: 0, size: 20, total: items.length };
}

describe("Access Request Account Console route pages", () => {
    beforeEach(() => {
        Object.values(mocks.api).forEach((method) => method.mockReset());
        mocks.api.approve.mockResolvedValue(undefined);
        mocks.api.cancel.mockResolvedValue(undefined);
        mocks.api.catalog.mockResolvedValue(page([]));
        mocks.api.mine.mockResolvedValue(page([]));
        mocks.api.pending.mockResolvedValue(page([]));
        mocks.api.requestDetails.mockResolvedValue(undefined);
        mocks.api.reject.mockResolvedValue(undefined);
        mocks.api.submitRequest.mockResolvedValue(undefined);
        mocks.createAccessRequestsApi.mockReset().mockReturnValue(mocks.api);
        mocks.keycloak.updateToken.mockReset().mockResolvedValue(false);
    });

    it("loads the catalog with the Account Console token and submits a request", async () => {
        const user = userEvent.setup();
        mocks.api.catalog.mockResolvedValue(page([
            {
                alreadyGranted: false,
                description: "Read finance reports",
                displayName: "Finance Reader",
                id: "finance-reader",
                pendingRequest: false,
                resourceType: "CLIENT_ROLE",
                riskLevel: "LOW"
            }
        ]));

        renderRoutePage(<RequestAccessRoutePage />);

        const card = await screen.findByRole("article", { name: "Finance Reader" });
        await user.click(within(card).getByRole("button", { name: "Request access" }));
        const dialog = screen.getByRole("dialog", { name: "Request access to Finance Reader" });
        await user.type(within(dialog).getByLabelText("Justification"), "I need month-end reports.");
        await user.click(within(dialog).getByRole("button", { name: "Submit request" }));

        await waitFor(() => expect(mocks.api.submitRequest).toHaveBeenCalledWith({
            entitlementId: "finance-reader",
            justification: "I need month-end reports."
        }));
        await waitFor(() => expect(mocks.api.catalog).toHaveBeenCalledTimes(2));

        expect(mocks.createAccessRequestsApi).toHaveBeenCalledWith(expect.objectContaining({
            realm: "finance",
            serverBaseUrl: "https://keycloak.example"
        }));
        const [{ getAccessToken }] = mocks.createAccessRequestsApi.mock.calls.at(-1)!;
        await expect(getAccessToken()).resolves.toBe("account-console-token");
        expect(mocks.keycloak.updateToken).toHaveBeenCalledWith(30);
    });

    it("loads requester requests and cancels a pending request", async () => {
        const user = userEvent.setup();
        mocks.api.mine.mockResolvedValue(page([
            {
                createdAt: "2026-09-03T10:00:00Z",
                decisionStatus: "PENDING",
                entitlementId: "finance-reader",
                id: "request-1",
                provisioningStatus: "NOT_STARTED",
                resourceName: "Finance Reader",
                resourceType: "CLIENT_ROLE"
            }
        ]));

        renderRoutePage(<MyRequestsRoutePage />);

        const card = await screen.findByRole("article", { name: "Finance Reader" });
        await user.click(within(card).getByRole("button", { name: "Cancel request" }));

        await waitFor(() => expect(mocks.api.cancel).toHaveBeenCalledWith("request-1"));
        await waitFor(() => expect(mocks.api.mine).toHaveBeenCalledTimes(2));
    });

    it("loads the selected request's full detail before displaying its history", async () => {
        const user = userEvent.setup();
        mocks.api.mine.mockResolvedValue(page([
            {
                createdAt: "2026-09-03T10:00:00Z",
                decisionStatus: "APPROVED",
                entitlementId: "finance-reader",
                id: "request-1",
                provisioningStatus: "SUCCEEDED",
                resourceName: "Finance Reader",
                resourceType: "CLIENT_ROLE"
            }
        ]));
        mocks.api.requestDetails.mockResolvedValue({
            createdAt: "2026-09-03T10:00:00Z",
            decision: {
                approverId: "finance-approver",
                comment: "Approved for month-end.",
                decidedAt: "2026-09-03T10:05:00Z"
            },
            decisionStatus: "APPROVED",
            entitlementId: "finance-reader",
            history: [
                { type: "REQUEST_CREATED", occurredAt: "2026-09-03T10:00:00Z" },
                { type: "REQUEST_APPROVED", occurredAt: "2026-09-03T10:05:00Z" }
            ],
            id: "request-1",
            justification: "I need month-end reports.",
            provisioningStatus: "SUCCEEDED",
            resourceName: "Finance Reader",
            resourceType: "CLIENT_ROLE"
        });

        renderRoutePage(<MyRequestsRoutePage />);

        const card = await screen.findByRole("article", { name: "Finance Reader" });
        await user.click(within(card).getByRole("button", { name: "View details" }));

        const dialog = await screen.findByRole("dialog", { name: "Finance Reader request details" });
        await waitFor(() => expect(mocks.api.requestDetails).toHaveBeenCalledWith("request-1"));
        expect(within(dialog).getByText("I need month-end reports.")).toBeVisible();
        expect(within(dialog).getByText("finance-approver")).toBeVisible();
        expect(within(dialog).getByText("Approved for month-end.")).toBeVisible();
        expect(within(dialog).getByText("REQUEST_CREATED")).toBeVisible();
        expect(within(dialog).getByText("REQUEST_APPROVED")).toBeVisible();
    });

    it("loads pending approvals and approves a request", async () => {
        const user = userEvent.setup();
        mocks.api.pending.mockResolvedValue(page([
            {
                createdAt: "2026-09-03T10:00:00Z",
                entitlementId: "finance-reader",
                id: "request-1",
                justification: "I need month-end reports.",
                requesterId: "anass",
                resourceName: "Finance Reader",
                resourceType: "CLIENT_ROLE",
                riskLevel: "LOW"
            }
        ]));

        renderRoutePage(<ApprovalsRoutePage />);

        const card = await screen.findByRole("article", { name: "Finance Reader requested by anass" });
        await user.click(within(card).getByRole("button", { name: "Approve" }));
        const dialog = screen.getByRole("dialog", { name: "Approve Finance Reader" });
        await user.type(within(dialog).getByLabelText("Decision comment"), "Approved.");
        await user.click(within(dialog).getByRole("button", { name: "Confirm approval" }));

        await waitFor(() => expect(mocks.api.approve).toHaveBeenCalledWith("request-1", { comment: "Approved." }));
        await waitFor(() => expect(mocks.api.pending).toHaveBeenCalledTimes(2));
    });

    it("shows a retryable page-level error when loading fails", async () => {
        const user = userEvent.setup();
        mocks.api.catalog.mockRejectedValue(new Error("Catalog unavailable"));

        renderRoutePage(<RequestAccessRoutePage />);

        const alert = await screen.findByRole("alert");
        expect(alert).toHaveTextContent("Unable to load access requests.");
        expect(alert).toHaveTextContent("Catalog unavailable");
        await user.click(within(alert).getByRole("button", { name: "Retry" }));
        await waitFor(() => expect(mocks.api.catalog).toHaveBeenCalledTimes(2));
    });
});
