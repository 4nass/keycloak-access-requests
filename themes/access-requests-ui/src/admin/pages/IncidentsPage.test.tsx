import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createInstance } from "i18next";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AdminIncident, AdminIncidentPage } from "../api/EntitlementsAdminApi";

const api = vi.hoisted(() => ({
    incidents: vi.fn(),
    retryNotificationDelivery: vi.fn(),
    retryFailedProvisioning: vi.fn(),
    closeFailedProvisioning: vi.fn(),
    retryGrantRevocation: vi.fn(),
    resolveGrantRevocation: vi.fn()
}));
vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => api }));

import { IncidentsPage } from "./IncidentsPage";

const i18n = createInstance();
await i18n.init({
    initImmediate: false,
    lng: "en",
    resources: { en: { translation: {
        accessRequestsAdminIncidents: "Incidents",
        accessRequestsAdminIncidentsType: "Type",
        accessRequestsAdminIncidentsState: "Status",
        accessRequestsAdminIncidentsAllTypes: "All types",
        accessRequestsAdminIncidentsNotification: "Notification",
        accessRequestsAdminIncidentsProvisioning: "Provisioning",
        accessRequestsAdminIncidentsRevocation: "Revocation",
        accessRequestsAdminIncidentsOpen: "Open",
        accessRequestsAdminIncidentsClosed: "Closed or resolved",
        accessRequestsAdminIncidentsAllStates: "All statuses",
        accessRequestsAdminIncidentsEmpty: "No incidents match these filters.",
        accessRequestsAdminIncidentsOccurredAt: "Last activity",
        accessRequestsAdminIncidentsDescription: "Review incidents.",
        accessRequestsAdminIncidentsDiagnosticPending: "Cause pending refresh.",
        accessRequestsAdminIncidentsDateFilters: "Dates",
        accessRequestsAdminIncidentsApplyDates: "Apply dates",
        accessRequestsAdminIncidentsInvalidDates: "The start date must not be after the end date.",
        accessRequestsAdminIncidentsPerPage: "Per page",
        accessRequestsAdminIncidentsPrevious: "Previous",
        accessRequestsAdminIncidentsNext: "Next",
        accessRequestsAdminIncidentsPage: "Page {{page}}",
        accessRequestsAdminEventsFrom: "From date",
        accessRequestsAdminEventsTo: "To date",
        accessRequestsAdminEventsClear: "Clear filters",
        accessRequestsAdminEventsRequest: "Request",
        accessRequestsAdminFailedProvisioningRequester: "Requester",
        accessRequestsAdminNotificationDeliveryRecipient: "Recipient",
        accessRequestsAdminNotificationDeliveryRecipientType: "Recipient type",
        accessRequestsAdminNotificationRecipientTypeRealmRole: "Realm role",
        accessRequestsAdminNotificationDeliveryRetry: "Retry notification",
        accessRequestsAdminNotificationDeliveryRetryDescription: "Retry this notification.",
        accessRequestsAdminNotificationDeliveryRetryQueued: "Notification queued for retry.",
        accessRequestsAdminFailedProvisioningRetry: "Retry provisioning",
        accessRequestsAdminFailedProvisioningRetryDescription: "Retry access.",
        accessRequestsAdminFailedProvisioningRetrySuccess: "Provisioning succeeded.",
        accessRequestsAdminFailedProvisioningClose: "Close failure",
        accessRequestsAdminFailedProvisioningCloseReason: "Closure reason",
        accessRequestsAdminFailedProvisioningCloseDescription: "Record why this is closed.",
        accessRequestsAdminFailedProvisioningCloseSuccess: "Failure closed.",
        accessRequestsAdminRevocationRetry: "Retry revocation",
        accessRequestsAdminRevocationRetryWarning: "Retry removal.",
        accessRequestsAdminRevocationRetrySuccess: "Access removed.",
        accessRequestsAdminRevocationConfirmRemoved: "Confirm removal",
        accessRequestsAdminRevocationConfirmRemovedWarning: "Confirm only after removal.",
        accessRequestsAdminRevocationResolutionReason: "Resolution reason",
        accessRequestsAdminRevocationConfirmedRemoved: "Removal confirmed.",
        accessRequestsAdminFailureCause: "Cause",
        accessRequestsAdminFailureRequesterMissing: "Requester missing",
        accessRequestsAdminRevocationRemovalFailed: "Removal failed",
        accessRequestsAdminNotAvailable: "Not available",
        accessRequestsAdminUserUnavailable: "User unavailable",
        accessRequestsAdminCancel: "Cancel",
        accessRequestsAdminErrorUnavailable: "Unable to load incidents.",
        close: "Close",
        loading: "Loading",
        reload: "Reload"
    } } }
});

const notification: AdminIncident = {
    type: "NOTIFICATION", id: "mail-1", occurredAt: "2026-10-12T10:00:00Z", state: "OPEN",
    notification: {
        id: "mail-1", requestId: "request-1", requestName: "Wiki access",
        entitlementId: "entitlement-1", entitlementName: "Wiki access",
        recipientId: "role-1", recipientName: "Approvers", recipientType: "REALM_ROLE",
        notificationType: "REQUEST_SUBMITTED", attemptCount: 10
    }, provisioning: null, revocation: null
};
const provisioning: AdminIncident = {
    type: "PROVISIONING", id: "request-2", occurredAt: "2026-10-12T09:00:00Z", state: "OPEN",
    notification: null, revocation: null,
    provisioning: {
        id: "request-2", requesterId: "user-2", requesterName: "Sam",
        entitlementId: "entitlement-2", entitlementName: "Project access",
        resourceType: "REALM_ROLE", resourceName: "Project access", decisionStatus: "APPROVED",
        provisioningStatus: "FAILED", failureCode: "REQUESTER_MISSING", updatedAt: "2026-10-12T09:00:00Z",
        closedAt: null, closedBy: null, closureReason: null
    }
};
const revocation: AdminIncident = {
    type: "REVOCATION", id: "request-3", occurredAt: "2026-10-12T08:00:00Z", state: "OPEN",
    notification: null, provisioning: null,
    revocation: {
        requestId: "request-3", requesterId: "user-3", requesterName: "Alex",
        entitlementId: "entitlement-3", entitlementName: "Production access", resourceType: "REALM_ROLE",
        resourceId: "role-3", deliveryGroupId: "group-3", expiresAt: "2026-10-12T07:00:00Z",
        failureCode: "REMOVAL_FAILED", attemptCount: 2, firstFailedAt: "2026-10-12T07:00:00Z",
        lastFailedAt: "2026-10-12T08:00:00Z", nextAttemptAt: "2026-10-12T09:00:00Z", resolvedAt: null
    }
};

function page(items: AdminIncident[], nextCursor: string | null = null): AdminIncidentPage {
    return { items, size: 20, nextCursor };
}

function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((fulfill) => { resolve = fulfill; });
    return { promise, resolve };
}

function renderPage() {
    return render(<I18nextProvider i18n={i18n}><IncidentsPage /></I18nextProvider>);
}

describe("IncidentsPage", () => {
    beforeEach(() => {
        for (const mock of Object.values(api)) mock.mockReset();
        api.incidents.mockResolvedValue(page([notification, provisioning, revocation]));
        api.retryNotificationDelivery.mockResolvedValue(undefined);
        api.retryFailedProvisioning.mockResolvedValue({ provisioningStatus: "SUCCEEDED" });
        api.closeFailedProvisioning.mockResolvedValue(undefined);
        api.retryGrantRevocation.mockResolvedValue({ status: "REVOKED" });
        api.resolveGrantRevocation.mockResolvedValue(undefined);
    });

    it("loads all open types in one worklist and filters on the server", async () => {
        renderPage();
        expect(await screen.findByRole("heading", { name: "Wiki access" })).toBeVisible();
        expect(screen.getByRole("heading", { name: "Project access" })).toBeVisible();
        expect(screen.getByRole("heading", { name: "Production access" })).toBeVisible();
        expect(api.incidents).toHaveBeenCalledWith({ type: "ALL", state: "OPEN",
            from: undefined, to: undefined, cursor: undefined, size: 20 });

        fireEvent.change(screen.getByLabelText("Type"), { target: { value: "NOTIFICATION" } });
        await waitFor(() => expect(api.incidents).toHaveBeenCalledWith({
            type: "NOTIFICATION", state: "OPEN", from: undefined, to: undefined,
            cursor: undefined, size: 20
        }));
        expect(screen.getByRole("option", { name: "Closed or resolved" })).toBeDisabled();
    });

    it("does not show pagination controls for an empty or single-page result", async () => {
        api.incidents.mockResolvedValue(page([]));
        renderPage();
        expect(await screen.findByText("No incidents match these filters.")).toBeVisible();
        expect(screen.queryByRole("button", { name: "Next" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Previous" })).not.toBeInTheDocument();
    });

    it("ignores an older response after changing filters", async () => {
        const slow = deferred<AdminIncidentPage>();
        api.incidents.mockReset().mockReturnValueOnce(slow.promise).mockResolvedValueOnce(page([revocation]));
        renderPage();
        fireEvent.change(screen.getByLabelText("Type"), { target: { value: "REVOCATION" } });
        expect(await screen.findByRole("heading", { name: "Production access" })).toBeVisible();
        await act(async () => slow.resolve(page([notification])));
        expect(screen.getByRole("heading", { name: "Production access" })).toBeVisible();
        expect(screen.queryByRole("heading", { name: "Wiki access" })).not.toBeInTheDocument();
    });

    it("follows a server cursor and hides stale actions while loading", async () => {
        const nextPage = deferred<AdminIncidentPage>();
        api.incidents.mockReset().mockResolvedValue(page([notification], "after-first"))
            .mockResolvedValueOnce(page([notification], "after-first"))
            .mockReturnValueOnce(nextPage.promise);
        renderPage();
        expect(await screen.findByRole("heading", { name: "Wiki access" })).toBeVisible();
        fireEvent.click(screen.getAllByRole("button", { name: "Next" })[0]);
        await waitFor(() => expect(api.incidents).toHaveBeenCalledWith({
            type: "ALL", state: "OPEN", from: undefined, to: undefined,
            cursor: "after-first", size: 20
        }));
        expect(screen.queryByRole("button", { name: "Retry notification" })).not.toBeInTheDocument();
        await act(async () => nextPage.resolve(page([revocation])));
        expect(screen.getByRole("heading", { name: "Production access" })).toBeVisible();
        fireEvent.click(screen.getAllByRole("button", { name: "Previous" })[0]);
        await waitFor(() => expect(api.incidents).toHaveBeenLastCalledWith({
            type: "ALL", state: "OPEN", from: undefined, to: undefined,
            cursor: undefined, size: 20
        }));
    });

    it("applies a local-day date range and resets the cursor", async () => {
        renderPage();
        expect(await screen.findByRole("heading", { name: "Wiki access" })).toBeVisible();
        fireEvent.click(screen.getByRole("button", { name: "Dates" }));
        fireEvent.change(screen.getByLabelText("From date"), { target: { value: "2026-10-12" } });
        fireEvent.change(screen.getByLabelText("To date"), { target: { value: "2026-10-11" } });
        fireEvent.click(screen.getByRole("button", { name: "Apply dates" }));
        expect(screen.getByText("The start date must not be after the end date.")).toBeVisible();
        fireEvent.change(screen.getByLabelText("To date"), { target: { value: "2026-10-13" } });
        fireEvent.click(screen.getByRole("button", { name: "Apply dates" }));
        await waitFor(() => expect(api.incidents).toHaveBeenCalledWith({
            type: "ALL", state: "OPEN", cursor: undefined, size: 20,
            from: new Date(2026, 9, 12).toISOString(),
            to: new Date(2026, 9, 13, 23, 59, 59, 999).toISOString()
        }));
    });

    it("removes a requeued notification even when the refresh fails", async () => {
        api.incidents.mockReset().mockResolvedValueOnce(page([notification]))
            .mockRejectedValueOnce(new Error("network down"));
        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Retry notification" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry notification" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry notification" }));
        await waitFor(() => expect(api.retryNotificationDelivery).toHaveBeenCalledWith("mail-1"));
        expect(await screen.findByText("Notification queued for retry.")).toBeVisible();
        expect(screen.queryByRole("heading", { name: "Wiki access" })).not.toBeInTheDocument();
        expect(await screen.findByText("accessRequestsAdminErrorUnexpected")).toBeVisible();
    });

    it("requires an operational reason and calls the existing closure API", async () => {
        api.incidents.mockResolvedValue(page([provisioning]));
        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Close failure" }));
        const dialog = await screen.findByRole("dialog", { name: "Close failure" });
        expect(within(dialog).getByRole("button", { name: "Close failure" })).toBeDisabled();
        fireEvent.change(within(dialog).getByRole("textbox"), {
            target: { value: "Requester account was deleted" }
        });
        fireEvent.click(within(dialog).getByRole("button", { name: "Close failure" }));
        await waitFor(() => expect(api.closeFailedProvisioning)
            .toHaveBeenCalledWith("request-2", "Requester account was deleted"));
    });

    it("shows resolved incidents without retry controls", async () => {
        api.incidents.mockResolvedValue(page([{ ...revocation, state: "CLOSED",
            revocation: { ...revocation.revocation!, resolvedAt: "2026-10-12T11:00:00Z" } }]));
        renderPage();
        fireEvent.change(screen.getByLabelText("Status"), { target: { value: "CLOSED" } });
        expect(await screen.findByRole("heading", { name: "Production access" })).toBeVisible();
        expect(screen.queryByRole("button", { name: "Retry revocation" })).not.toBeInTheDocument();
        expect(api.incidents).toHaveBeenCalledWith({ type: "ALL", state: "CLOSED",
            from: undefined, to: undefined, cursor: undefined, size: 20 });
    });
});
