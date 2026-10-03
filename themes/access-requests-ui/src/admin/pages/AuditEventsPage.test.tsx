import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ auditEvents: vi.fn(), auditRequest: vi.fn(), auditUsers: vi.fn(), capabilities: vi.fn() }));
vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => api }));

import { AuditEventsPage, localAuditDayBoundary } from "./AuditEventsPage";
import { AuditEventsRoute } from "./AuditEventsRoute";
import { AuditRequestDetailsPage } from "./AuditRequestDetailsPage";

const event = {
    id: "event-1", requestId: "request-1", requestName: "Finance access",
    actorId: "approver-1", actorName: "Morgan Approver",
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
        api.auditUsers.mockReset().mockResolvedValue({ items: [] });
        api.capabilities.mockReset().mockResolvedValue({ canManageCatalog: true });
    });

    it("filters, paginates and links to the realm-scoped request detail", async () => {
        const user = userEvent.setup();
        api.auditEvents.mockResolvedValue({ items: [event], page: 0, size: 20, total: 25 });
        renderPage();

        expect(await screen.findByRole("table", { name: "accessRequestsAdminEvents" })).toBeVisible();
        expect(screen.getByText("accessRequestsAdminEventRequestApproved")).toBeVisible();
        expect(screen.getByRole("link", { name: /Finance access/ })).toHaveAttribute(
            "href", "/master/access-requests/requests/request-1"
        );
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
        expect(screen.getByRole("button", { name: "accessRequestsAdminEventsHideFilters" })).toHaveAttribute("aria-expanded", "true");
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminEventsRequest" }), "request-1");
        expect(api.auditEvents).toHaveBeenCalledTimes(1);
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsApply" }));
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({ requestId: "request-1" })));
        await user.click(screen.getAllByRole("button", { name: /next page/i })[0]);
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })));
    });

    it("uses local calendar-day boundaries without changing the selected dates", async () => {
        vi.stubEnv("TZ", "Europe/Paris");
        try {
            renderPage();
            fireEvent.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
            fireEvent.change(screen.getByLabelText("accessRequestsAdminEventsFrom"),
                { target: { value: "2026-09-24" } });
            fireEvent.change(screen.getByLabelText("accessRequestsAdminEventsTo"),
                { target: { value: "2026-09-24" } });
            fireEvent.click(screen.getByRole("button", { name: "accessRequestsAdminEventsApply" }));
            await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({
                from: "2026-09-23T22:00:00.000Z", to: "2026-09-24T21:59:59.999Z"
            })));
            fireEvent.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
            expect(screen.getByLabelText("accessRequestsAdminEventsFrom")).toHaveValue("2026-09-24");
            expect(screen.getByLabelText("accessRequestsAdminEventsTo")).toHaveValue("2026-09-24");
            expect(localAuditDayBoundary("2026-03-29", true)).toBe("2026-03-29T21:59:59.999Z");
        } finally {
            vi.unstubAllEnvs();
        }
    });

    it("shows removable applied filters and restores the unfiltered event list", async () => {
        const user = userEvent.setup();
        renderPage();
        expect(await screen.findByRole("table", { name: "accessRequestsAdminEvents" })).toBeVisible();
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
        await user.selectOptions(screen.getByLabelText("accessRequestsAdminEventsType"), "REQUEST_APPROVED");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsApply" }));
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({ type: "REQUEST_APPROVED" })));
        expect(screen.getByText(/accessRequestsAdminEventsType: accessRequestsAdminEventRequestApproved/)).toBeVisible();
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsClear" }));
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({ type: undefined })));
    });

    it("selects requester and event actor by name without showing their IDs as filters", async () => {
        const user = userEvent.setup();
        api.auditUsers.mockImplementation(async (search: string) => ({ items: search.startsWith("Alex")
            ? [{ id: "requester-1", name: "Alex Reader", username: "alex" }]
            : [{ id: "approver-1", name: "Morgan Approver", username: "morgan" }] }));
        renderPage();
        await screen.findByRole("table", { name: "accessRequestsAdminEvents" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
        await user.type(screen.getByRole("searchbox", { name: "accessRequestsAdminEventsSearchRequester" }), "Alex");
        await waitFor(() => expect(api.auditUsers).toHaveBeenCalledWith("Alex", expect.any(AbortSignal)));
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminEventsRequester" }), "requester-1");
        await user.type(screen.getByRole("searchbox", { name: "accessRequestsAdminEventsSearchActor" }), "Morgan");
        await waitFor(() => expect(api.auditUsers).toHaveBeenCalledWith("Morgan", expect.any(AbortSignal)));
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminEventsActor" }), "approver-1");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsApply" }));
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({
            requesterId: "requester-1", actorId: "approver-1"
        })));
        expect(screen.getByText(/accessRequestsAdminEventsRequester: Alex Reader/)).toBeVisible();
        expect(screen.getByText(/accessRequestsAdminEventsActor: Morgan Approver/)).toBeVisible();
        expect(screen.queryByText(/requester-1|approver-1/)).not.toBeInTheDocument();
    });

    it("offers the automatic actor without requiring a user search", async () => {
        const user = userEvent.setup();
        renderPage();
        await screen.findByRole("table", { name: "accessRequestsAdminEvents" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminEventsActor" }),
            "access-requests-expiration");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsApply" }));
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({
            actorId: "access-requests-expiration", requesterId: undefined
        })));
        expect(api.auditUsers).not.toHaveBeenCalled();
    });

    it("discards stale person-search results after the query changes", async () => {
        let resolveOld!: (value: { items: { id: string; name: string; username: string }[] }) => void;
        api.auditUsers.mockImplementation((search: string) => search === "Alex"
            ? new Promise((resolve) => { resolveOld = resolve; })
            : Promise.resolve({ items: [{ id: "new-user", name: "Morgan", username: "morgan" }] }));
        renderPage();
        await screen.findByRole("table", { name: "accessRequestsAdminEvents" });
        fireEvent.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
        const search = screen.getByRole("searchbox", { name: "accessRequestsAdminEventsSearchRequester" });
        fireEvent.change(search, { target: { value: "Alex" } });
        await waitFor(() => expect(api.auditUsers).toHaveBeenCalledWith("Alex", expect.any(AbortSignal)));
        fireEvent.change(search, { target: { value: "Morgan" } });
        await waitFor(() => expect(screen.getByRole("option", { name: "Morgan (morgan)" })).toBeVisible());
        resolveOld({ items: [{ id: "old-user", name: "Alex", username: "alex" }] });
        expect(screen.queryByRole("option", { name: "Alex (alex)" })).not.toBeInTheDocument();
    });

    it("opens the request over the event list and restores the filtered list on Escape", async () => {
        const user = userEvent.setup();
        api.auditRequest.mockReset().mockResolvedValue({
            id: "request-1", requesterName: "Alex Reader", entitlementName: "Finance access",
            resourceName: "Finance", decisionStatus: "APPROVED", provisioningStatus: "SUCCEEDED",
            createdAt: "2026-09-24T10:00:00Z", justification: "I need access.",
            history: [{ type: "REQUEST_APPROVED", actorId: "approver-1", actorName: "Morgan Approver",
                occurredAt: "2026-09-24T10:01:00Z" }]
        });
        render(<MemoryRouter initialEntries={["/master/access-requests/events"]}>
            <Routes><Route element={<AuditEventsPage />}>
                <Route path="/:realm/access-requests/events" element={<></>} />
                <Route path="/:realm/access-requests/requests/:requestId" element={<AuditRequestDetailsPage />} />
            </Route></Routes>
        </MemoryRouter>);

        await screen.findByRole("table", { name: "accessRequestsAdminEvents" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
        await user.selectOptions(screen.getByLabelText("accessRequestsAdminEventsType"), "REQUEST_APPROVED");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsApply" }));
        await waitFor(() => expect(api.auditEvents).toHaveBeenLastCalledWith(expect.objectContaining({
            type: "REQUEST_APPROVED"
        })));
        const listCalls = api.auditEvents.mock.calls.length;
        await user.click(screen.getByRole("link", { name: "Finance access" }));
        const dialog = await screen.findByRole("dialog", {
            name: "accessRequestsAdminEventsRequestTitle: Finance access"
        });
        expect(dialog).toBeVisible();
        expect(within(dialog).getByText("accessRequestsAdminEventRequestApproved")).toBeVisible();
        await user.keyboard("{Escape}");
        await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
        expect(screen.getByText(/accessRequestsAdminEventsType: accessRequestsAdminEventRequestApproved/)).toBeVisible();
        expect(api.auditEvents).toHaveBeenCalledTimes(listCalls);
    });

    it("hides results from the old filter while loading and after a failed search", async () => {
        api.auditEvents.mockResolvedValueOnce({ items: [event], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(Object.assign(new Error("secret backend failure"), { code: "HTTP_503", status: 503 }));
        renderPage();

        expect(await screen.findByText("accessRequestsAdminEventRequestApproved")).toBeVisible();
        fireEvent.click(screen.getByRole("button", { name: "accessRequestsAdminEventsFilters" }));
        fireEvent.change(screen.getByRole("textbox", { name: "accessRequestsAdminEventsRequest" }),
            { target: { value: "request-1" } });
        expect(screen.getByRole("link", { name: /Finance access/ })).toBeVisible();
        fireEvent.click(screen.getByRole("button", { name: "accessRequestsAdminEventsApply" }));
        expect(screen.queryByRole("link", { name: /Finance access/ })).not.toBeInTheDocument();
        expect(await screen.findByText("accessRequestsAdminErrorUnavailable")).toBeVisible();
        expect(screen.queryByRole("link", { name: /Finance access/ })).not.toBeInTheDocument();
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
    beforeEach(() => {
        api.capabilities.mockReset().mockResolvedValue({ canManageCatalog: true });
    });

    it("renders request history without raw event metadata", async () => {
        const user = userEvent.setup();
        api.auditRequest.mockReset().mockResolvedValue({
            id: "request-1", requesterId: "requester-1", requesterName: "Alex Reader",
            entitlementId: "entitlement-1", entitlementName: "Finance access", resourceName: "Finance",
            decisionStatus: "APPROVED", provisioningStatus: "SUCCEEDED", provisioningClosedAt: null,
            createdAt: "2026-09-24T10:00:00Z", justification: "I need access.",
            decision: null, history: [{ type: "REQUEST_APPROVED", actorId: "approver-1",
                actorName: "Morgan Approver",
                occurredAt: "2026-09-24T10:01:00Z", failureCode: null, closureReason: null }]
        });
        render(<MemoryRouter initialEntries={["/master/access-requests/requests/request-1"]}>
            <Routes>
                <Route path="/:realm/access-requests/requests/:requestId" element={<AuditRequestDetailsPage />} />
                <Route path="/:realm/access-requests/events" element={<div>Events list</div>} />
            </Routes>
        </MemoryRouter>);

        expect((await screen.findAllByText("Finance access")).length).toBeGreaterThanOrEqual(1);
        expect(screen.getByRole("dialog", { name: "accessRequestsAdminEventsRequestTitle: Finance access" }))
            .toBeVisible();
        const dialog = screen.getByRole("dialog");
        expect(dialog.querySelector("dl")).toHaveClass("pf-m-3-col-on-lg");
        expect(within(dialog).getByRole("list", { name: "accessRequestsAdminEventsHistory" }))
            .toBeVisible();
        expect(screen.getByText("accessRequestsAdminEventRequestApproved")).toBeVisible();
        expect(screen.getByText("Alex Reader")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminDecisionApproved")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminProvisioningSucceeded")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminEventsActor: Morgan Approver")).toBeVisible();
        expect(api.auditRequest).toHaveBeenCalledWith("request-1", { page: 0, size: 20 });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEventsClose" }));
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(screen.getByText("Events list")).toBeVisible();
    });

    it("pages long request histories without keeping the previous page visible", async () => {
        const user = userEvent.setup();
        const detail = {
            id: "request-1", requesterId: "requester-1", entitlementId: "entitlement-1",
            resourceName: "Finance", decisionStatus: "APPROVED", provisioningStatus: "FAILED",
            provisioningClosedAt: null, createdAt: "2026-09-24T10:00:00Z", justification: "Need access.",
            decision: null, historySize: 20, historyTotal: 21
        };
        api.auditRequest.mockReset()
            .mockResolvedValueOnce({ ...detail, historyPage: 0, history: [{
                type: "REQUEST_CREATED", actorId: "requester-1", occurredAt: "2026-09-24T10:00:00Z"
            }] })
            .mockResolvedValueOnce({ ...detail, historyPage: 1, history: [{
                type: "PROVISIONING_FAILED", actorId: "approver-2", occurredAt: "2026-09-24T10:02:00Z"
            }] });
        render(<MemoryRouter initialEntries={["/master/access-requests/requests/request-1"]}>
            <Routes><Route path="/:realm/access-requests/requests/:requestId" element={<AuditRequestDetailsPage />} /></Routes>
        </MemoryRouter>);

        expect(await screen.findByText("accessRequestsAdminEventRequestCreated")).toBeVisible();
        await user.click(screen.getByRole("button", { name: /next page/i }));
        await waitFor(() => expect(api.auditRequest).toHaveBeenCalledWith("request-1", { page: 1, size: 20 }));
        expect(await screen.findByText("accessRequestsAdminEventProvisioningFailed")).toBeVisible();
        expect(screen.queryByText("accessRequestsAdminEventRequestCreated")).not.toBeInTheDocument();
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
