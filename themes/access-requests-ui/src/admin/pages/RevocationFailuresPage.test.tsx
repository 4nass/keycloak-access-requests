import { createInstance } from "i18next";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
    api: { revocationFailures: vi.fn(), retryGrantRevocation: vi.fn(), resolveGrantRevocation: vi.fn() }
}));

vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => mocks.api }));

import { RevocationFailuresPage } from "./RevocationFailuresPage";

const i18n = createInstance();
await i18n.init({ initImmediate: false, lng: "en", resources: { en: { translation: {
    accessRequestsAdminRevocationFailures: "Revocation failures",
    accessRequestsAdminRevocationFailuresDescription: "Review expired access.",
    accessRequestsAdminRevocationOpen: "Open failures",
    accessRequestsAdminRevocationResolved: "Resolved failures",
    accessRequestsAdminRevocationRetry: "Retry revocation",
    accessRequestsAdminRevocationConfirmRemoved: "Confirm removal",
    accessRequestsAdminRevocationConfirmRemovedWarning: "Server verifies absence.",
    accessRequestsAdminRevocationResolutionReason: "Resolution reason",
    accessRequestsAdminRevocationConfirmedRemoved: "Membership absence confirmed.",
    accessRequestsAdminRevocationRetryWarning: "Only package membership is removed.",
    accessRequestsAdminRevocationRetrySuccess: "Revocation succeeded.",
    accessRequestsAdminRevocationRetryFailed: "Revocation failed again.",
    accessRequestsAdminRevocationDiagnosticPending: "Latest diagnostic unavailable until refresh succeeds.",
    accessRequestsAdminRevocationRemovalFailed: "Removal failed.",
    accessRequestsAdminRevocationAuthorityUnverifiable: "Ownership cannot be verified.",
    accessRequestsAdminFailureCause: "Cause",
    accessRequestsAdminFailedProvisioningRequester: "Requester",
    accessRequestsAdminFailedProvisioningEntitlement: "Entitlement",
    accessRequestsAdminRevocationExpiredAt: "Expired at",
    accessRequestsAdminRevocationAttempts: "Attempts",
    accessRequestsAdminRevocationNextAttempt: "Next attempt",
    accessRequestsAdminRevocationResolvedAt: "Resolved at",
    accessRequestsAdminEventsViewRequest: "View request",
    accessRequestsAdminRevocationOpenEmpty: "No open failures.",
    accessRequestsAdminRevocationResolvedEmpty: "No resolved failures.",
    accessRequestsAdminErrorUnavailable: "Service unavailable.",
    accessRequestsAdminErrorUnexpected: "Unexpected error.",
    accessRequestsAdminCancel: "Cancel",
    reload: "Reload", loading: "Loading"
} } } });

const item = {
    requestId: "request-1", requesterId: "user-1", entitlementId: "entitlement-1",
    resourceType: "REALM_ROLE" as const, resourceId: "source-role", deliveryGroupId: "package-group",
    expiresAt: "2026-10-01T10:00:00Z", failureCode: "REMOVAL_FAILED" as const,
    attemptCount: 1, firstFailedAt: "2026-10-01T10:01:00Z",
    lastFailedAt: "2026-10-01T10:01:00Z", nextAttemptAt: "2026-10-01T10:06:00Z",
    resolvedAt: null
};

function renderPage() {
    return render(<I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={["/master/access-requests/revocation-failures"]}>
            <RevocationFailuresPage />
        </MemoryRouter>
    </I18nextProvider>);
}

describe("RevocationFailuresPage", () => {
    beforeEach(() => {
        mocks.api.revocationFailures.mockReset().mockResolvedValue({ items: [item], page: 0, size: 20, total: 1 });
        mocks.api.retryGrantRevocation.mockReset();
        mocks.api.resolveGrantRevocation.mockReset();
    });

    it("shows open incidents and keeps the resolved archive read-only", async () => {
        renderPage();
        expect(await screen.findByText("source-role")).toBeVisible();
        expect(screen.getByText("Removal failed.")).toBeVisible();
        fireEvent.click(screen.getByRole("tab", { name: "Resolved failures" }));
        await waitFor(() => expect(mocks.api.revocationFailures).toHaveBeenCalledWith({
            page: 0, size: 20, state: "RESOLVED"
        }));
        expect(screen.queryByRole("button", { name: "Retry revocation" })).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: "View request: request-1" })).toHaveAttribute(
            "href", "/master/access-requests/requests/request-1"
        );
    });

    it("does not keep old rows actionable when the next page fails to load", async () => {
        mocks.api.revocationFailures.mockResolvedValueOnce({ items: [item], page: 0, size: 20, total: 21 })
            .mockRejectedValueOnce(new TypeError("network"));
        renderPage();
        await screen.findByText("source-role");
        fireEvent.click(screen.getByLabelText("Go to next page"));
        await waitFor(() => expect(mocks.api.revocationFailures).toHaveBeenLastCalledWith({
            page: 1, size: 20, state: "OPEN"
        }));
        expect(await screen.findByText("Service unavailable.")).toBeVisible();
        expect(screen.queryByText("source-role")).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Retry revocation" })).not.toBeInTheDocument();
    });

    it("removes a successfully revoked row even if refresh fails", async () => {
        mocks.api.retryGrantRevocation.mockResolvedValue({ requestId: "request-1", status: "REVOKED", failureCode: null });
        mocks.api.revocationFailures.mockResolvedValueOnce({ items: [item], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(new TypeError("network"));
        renderPage();
        await screen.findByText("source-role");
        fireEvent.click(screen.getByRole("button", { name: "Retry revocation" }));
        fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Retry revocation" }));
        await screen.findByText("Revocation succeeded.");
        expect(screen.queryByText("source-role")).not.toBeInTheDocument();
        expect(await screen.findByText("Service unavailable.")).toBeVisible();
    });

    it("never presents an old failure code as the latest after a failed retry and refresh", async () => {
        mocks.api.retryGrantRevocation.mockResolvedValue({ requestId: "request-1", status: "FAILED",
            failureCode: "AUTHORITY_UNVERIFIABLE" });
        mocks.api.revocationFailures.mockResolvedValueOnce({ items: [item], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(new TypeError("network"));
        renderPage();
        await screen.findByText("Removal failed.");
        fireEvent.click(screen.getByRole("button", { name: "Retry revocation" }));
        fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Retry revocation" }));
        expect(await screen.findByText("Revocation failed again.")).toBeVisible();
        expect(screen.getByText("Latest diagnostic unavailable until refresh succeeds.")).toBeVisible();
        expect(screen.queryByText("Removal failed.")).not.toBeInTheDocument();
    });

    it("requires a reason and removes a verified externally resolved incident locally", async () => {
        mocks.api.resolveGrantRevocation.mockResolvedValue({ requestId: "request-1", status: "REVOKED" });
        mocks.api.revocationFailures.mockResolvedValueOnce({ items: [item], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(new TypeError("network"));
        renderPage();
        await screen.findByText("source-role");
        fireEvent.click(screen.getByRole("button", { name: "Confirm removal" }));
        const dialog = screen.getByRole("dialog");
        const confirm = within(dialog).getByRole("button", { name: "Confirm removal" });
        expect(confirm).toBeDisabled();
        fireEvent.change(within(dialog).getByRole("textbox"),
            { target: { value: "Removed by administrator" } });
        expect(confirm).toBeEnabled();
        fireEvent.click(confirm);
        await waitFor(() => expect(mocks.api.resolveGrantRevocation)
            .toHaveBeenCalledWith("request-1", "Removed by administrator"));
        await screen.findByText("Membership absence confirmed.");
        expect(screen.queryByText("source-role")).not.toBeInTheDocument();
    });
});
