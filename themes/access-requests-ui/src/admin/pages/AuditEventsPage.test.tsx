import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ auditEvents: vi.fn(), auditRequest: vi.fn(), capabilities: vi.fn() }));
vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => api }));

import { AuditEventsPage, localAuditDayBoundary } from "./AuditEventsPage";
import { AuditEventsRoute } from "./AuditEventsRoute";
import { AuditRequestDetailsPage } from "./AuditRequestDetailsPage";

const event = {
    id: "event-1", requestId: "request-1", actorId: "approver-1",
    type: "REQUEST_APPROVED", occurredAt: "2026-09-24T10:00:00Z"
};

function renderPage(component = <AuditEventsPage />) {
    return render(<MemoryRouter initialEntries={["/master/access-requests/events"]}>
        <Routes><Route path="/:realm/access-requests/events" element={component} /></Routes>
    </MemoryRouter>);
}

describe("Access request audit page", () => {
    beforeEach(() => {
        api.auditEvents.mockReset().mockResolvedValue({ items: [event], page: 0, size: 20, total: 1 });
        api.capabilities.mockReset().mockResolvedValue({ canManageCatalog: true });
    });

    it("filters, paginates and links to the realm-scoped request detail", async () => {
        const user = userEvent.setup();
        api.auditEvents.mockResolvedValue({ items: [event], page: 0, size: 20, total: 25 });
        renderPage();

        expect(await screen.findByText("accessRequestsAdminEventRequestApproved")).toBeVisible();
        expect(screen.getByRole("link", { name: /request-1/ })).toHaveAttribute(
            "href", "/master/access-requests/requests/request-1"
        );
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminEventsRequest" }), "request-1");
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({ requestId: "request-1" })));
        await user.click(screen.getAllByRole("button", { name: /next page/i })[0]);
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })));
    });

    it("uses local calendar-day boundaries without changing the selected dates", async () => {
        vi.stubEnv("TZ", "Europe/Paris");
        try {
            renderPage();
            fireEvent.change(screen.getByLabelText("accessRequestsAdminEventsFrom"),
                { target: { value: "2026-09-24" } });
            fireEvent.change(screen.getByLabelText("accessRequestsAdminEventsTo"),
                { target: { value: "2026-09-24" } });
            await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({
                from: "2026-09-23T22:00:00.000Z", to: "2026-09-24T21:59:59.999Z"
            })));
            expect(screen.getByLabelText("accessRequestsAdminEventsFrom")).toHaveValue("2026-09-24");
            expect(screen.getByLabelText("accessRequestsAdminEventsTo")).toHaveValue("2026-09-24");
            expect(localAuditDayBoundary("2026-03-29", true)).toBe("2026-03-29T21:59:59.999Z");
        } finally {
            vi.unstubAllEnvs();
        }
    });

    it("hides results from the old filter while loading and after a failed search", async () => {
        api.auditEvents.mockResolvedValueOnce({ items: [event], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(Object.assign(new Error("secret backend failure"), { code: "HTTP_503", status: 503 }));
        renderPage();

        expect(await screen.findByText("accessRequestsAdminEventRequestApproved")).toBeVisible();
        fireEvent.change(screen.getByRole("textbox", { name: "accessRequestsAdminEventsActor" }),
            { target: { value: "approver-1" } });
        expect(screen.queryByRole("link", { name: /request-1/ })).not.toBeInTheDocument();
        expect(await screen.findByText("accessRequestsAdminErrorUnavailable")).toBeVisible();
        expect(screen.queryByRole("link", { name: /request-1/ })).not.toBeInTheDocument();
        expect(screen.queryByText("secret backend failure")).not.toBeInTheDocument();
    });

    it("does not fetch history when the server denies catalog management", async () => {
        api.capabilities.mockResolvedValue({ canManageCatalog: false });
        renderPage(<AuditEventsRoute />);

        expect(await screen.findByRole("heading", { name: "accessRequestsAdminErrorForbidden" })).toBeVisible();
        expect(api.auditEvents).not.toHaveBeenCalled();
    });
});

describe("Administrative request detail", () => {
    it("renders request history without raw event metadata", async () => {
        api.auditRequest.mockReset().mockResolvedValue({
            id: "request-1", requesterId: "requester-1", entitlementId: "entitlement-1", resourceName: "Finance",
            decisionStatus: "APPROVED", provisioningStatus: "SUCCEEDED", provisioningClosedAt: null,
            createdAt: "2026-09-24T10:00:00Z", justification: "I need access.",
            decision: null, history: [{ type: "REQUEST_APPROVED", actorId: "approver-1",
                occurredAt: "2026-09-24T10:01:00Z", failureCode: null, closureReason: null }]
        });
        render(<MemoryRouter initialEntries={["/master/access-requests/requests/request-1"]}>
            <Routes><Route path="/:realm/access-requests/requests/:requestId" element={<AuditRequestDetailsPage />} /></Routes>
        </MemoryRouter>);

        expect(await screen.findByText("entitlement-1")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminEventRequestApproved")).toBeVisible();
        expect(screen.getByText("requester-1")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminDecisionApproved")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminProvisioningSucceeded")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminEventsActor: approver-1")).toBeVisible();
        expect(api.auditRequest).toHaveBeenCalledWith("request-1");
    });

    it("shows each safe failure code and the closure reason without technical diagnostics", async () => {
        api.auditRequest.mockReset().mockResolvedValue({
            id: "request-2", requesterId: "requester-2", entitlementId: "entitlement-2", resourceName: "Finance",
            decisionStatus: "APPROVED", provisioningStatus: "FAILED", provisioningClosedAt: "2026-09-24T10:05:00Z",
            createdAt: "2026-09-24T10:00:00Z", justification: "I need access.", decision: null,
            history: [
                { type: "PROVISIONING_FAILED", actorId: "approver-1", occurredAt: "2026-09-24T10:01:00Z",
                    failureCode: "RESOURCE_MISSING", closureReason: null },
                { type: "PROVISIONING_FAILED", actorId: "manager-1", occurredAt: "2026-09-24T10:03:00Z",
                    failureCode: "PROVIDER_UNAVAILABLE", closureReason: null },
                { type: "PROVISIONING_CLOSED", actorId: "manager-1", occurredAt: "2026-09-24T10:05:00Z",
                    failureCode: null, closureReason: "The role was permanently removed." }
            ]
        });
        render(<MemoryRouter initialEntries={["/master/access-requests/requests/request-2"]}>
            <Routes><Route path="/:realm/access-requests/requests/:requestId" element={<AuditRequestDetailsPage />} /></Routes>
        </MemoryRouter>);

        expect(await screen.findByText(/accessRequestsAdminFailureResourceMissing/)).toBeVisible();
        expect(screen.getByText(/accessRequestsAdminFailureProviderUnavailable/)).toBeVisible();
        expect(screen.getByText(/The role was permanently removed/)).toBeVisible();
        expect(screen.queryByText(/password=secret|Internal JDBC/)).not.toBeInTheDocument();
    });
});
