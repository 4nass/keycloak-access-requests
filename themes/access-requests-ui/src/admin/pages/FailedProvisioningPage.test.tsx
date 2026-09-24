import { createInstance } from "i18next";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
    api: {
        failedProvisioningRequests: vi.fn(),
        retryFailedProvisioning: vi.fn(),
        closeFailedProvisioning: vi.fn()
    }
}));

vi.mock("../api/useEntitlementsAdminApi", () => ({
    useEntitlementsAdminApi: () => mocks.api
}));

import { FailedProvisioningPage } from "./FailedProvisioningPage";

const i18n = createInstance();

await i18n.init({
    initImmediate: false,
    lng: "en",
    resources: {
        en: {
            translation: {
                accessRequestsAdminCancel: "Cancel",
                accessRequestsAdminErrorConflict: "The request changed or is no longer eligible for retry.",
                accessRequestsAdminErrorForbidden: "You do not have permission.",
                accessRequestsAdminErrorInvalidRequest: "The request is invalid.",
                accessRequestsAdminErrorNotFound: "The request no longer exists.",
                accessRequestsAdminErrorUnauthorized: "Your session is no longer authorized.",
                accessRequestsAdminErrorUnavailable: "The service is unavailable.",
                accessRequestsAdminErrorUnexpected: "The action could not be completed.",
                accessRequestsAdminFailedProvisioning: "Failed provisioning",
                accessRequestsAdminFailedProvisioningOpen: "Open failures",
                accessRequestsAdminFailedProvisioningClosed: "Closed failures",
                accessRequestsAdminFailedProvisioningClosedEmpty: "No closed provisioning failures.",
                accessRequestsAdminFailedProvisioningClosedAt: "Closed at",
                accessRequestsAdminFailedProvisioningClosedBy: "Closed by",
                accessRequestsAdminFailedProvisioningDescription: "Review approved requests that could not be provisioned.",
                accessRequestsAdminFailedProvisioningEmpty: "No failed provisioning requests.",
                accessRequestsAdminFailedProvisioningEntitlement: "Entitlement",
                accessRequestsAdminFailedProvisioningRequester: "Requester",
                accessRequestsAdminFailedProvisioningResource: "Resource",
                accessRequestsAdminFailedProvisioningRetry: "Retry provisioning",
                accessRequestsAdminFailedProvisioningRetryDescription: "Retry granting the approved entitlement.",
                accessRequestsAdminFailedProvisioningRetrySuccess: "Provisioning retry completed.",
                accessRequestsAdminFailedProvisioningRetryStillFailed: "Provisioning failed again.",
                accessRequestsAdminFailedProvisioningClose: "Close failure",
                accessRequestsAdminFailedProvisioningCloseDescription: "Close without granting access.",
                accessRequestsAdminFailedProvisioningCloseReason: "Closure reason",
                accessRequestsAdminFailedProvisioningCloseSuccess: "Failure closed.",
                accessRequestsAdminFailedProvisioningStatus: "Provisioning status",
                accessRequestsAdminFailureCause: "Failure cause",
                accessRequestsAdminFailureRequesterMissing: "The requester no longer exists.",
                accessRequestsAdminFailureResourceMissing: "The original resource is missing.",
                accessRequestsAdminFailureResourceTypeMismatch: "The resource type changed.",
                accessRequestsAdminFailureRealmMismatch: "The realm does not match.",
                accessRequestsAdminFailureProviderUnavailable: "The provider is unavailable.",
                accessRequestsAdminFailureUnexpected: "Check the server logs.",
                accessRequestsAdminFailureUnknown: "The cause is unavailable.",
                accessRequestsAdminProvisioningFailed: "Provisioning failed",
                accessRequestsAdminNotAvailable: "Not available",
                close: "Close",
                loading: "Loading",
                reload: "Reload"
            }
        }
    }
});

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

function renderPage() {
    return render(
        <I18nextProvider i18n={i18n}>
            <FailedProvisioningPage />
        </I18nextProvider>
    );
}

describe("FailedProvisioningPage", () => {
    beforeEach(() => {
        mocks.api.failedProvisioningRequests.mockReset().mockResolvedValue({
            items: [failedRequest],
            page: 0,
            size: 20,
            total: 1
        });
        mocks.api.retryFailedProvisioning.mockReset().mockResolvedValue({
            decisionStatus: "APPROVED",
            entitlementId: "finance-reader",
            id: "request-1",
            provisioningStatus: "SUCCEEDED"
        });
        mocks.api.closeFailedProvisioning.mockReset().mockResolvedValue({
            id: "request-1", decisionStatus: "APPROVED", provisioningStatus: "FAILED",
            closedAt: "2026-09-23T10:00:00Z", closedBy: "manager-1", reason: "The role was deleted."
        });
    });

    it("renders paginated failed request metadata without requester justification or internal failure details", async () => {
        renderPage();

        expect(await screen.findByText("Finance Reader")).toBeInTheDocument();
        expect(screen.getByText("user-1")).toBeInTheDocument();
        expect(screen.getByText("request-1")).toBeInTheDocument();
        expect(screen.getByText("Provisioning failed")).toBeInTheDocument();
        expect(screen.getByText("Failure cause")).toBeInTheDocument();
        expect(screen.getByText("The original resource is missing.")).toBeInTheDocument();
        expect(screen.queryByText(/failureReason|justification|stack trace/i)).not.toBeInTheDocument();
        expect(mocks.api.failedProvisioningRequests).toHaveBeenCalledWith({ page: 0, size: 20, state: "OPEN" });
    });

    it("warns about the safe failure cause before confirming a retry", async () => {
        renderPage();

        fireEvent.click(await screen.findByRole("button", { name: "Retry provisioning" }));

        const dialog = await screen.findByRole("dialog", { name: "Retry provisioning" });
        expect(within(dialog).getByText("The original resource is missing.")).toBeInTheDocument();
        expect(within(dialog).queryByText(/internal detail|stack trace/i)).not.toBeInTheDocument();
    });

    it("shows a safe fallback for legacy or unrecognized failure codes", async () => {
        mocks.api.failedProvisioningRequests.mockResolvedValue({
            items: [{ ...failedRequest, failureCode: "UNRECOGNIZED_INTERNAL_VALUE" }],
            page: 0,
            size: 20,
            total: 1
        });

        renderPage();

        expect(await screen.findByText("The cause is unavailable.")).toBeInTheDocument();
        expect(screen.queryByText("UNRECOGNIZED_INTERNAL_VALUE")).not.toBeInTheDocument();
    });

    it("shows a localized empty state when no provisioning failures remain", async () => {
        mocks.api.failedProvisioningRequests.mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });

        renderPage();

        expect(await screen.findByText("No failed provisioning requests.")).toBeInTheDocument();
    });

    it("loads the next request page through the server-side pagination contract", async () => {
        mocks.api.failedProvisioningRequests.mockResolvedValue({
            items: [failedRequest],
            page: 1,
            size: 20,
            total: 21
        });

        renderPage();
        await screen.findByText("request-1");
        fireEvent.click(screen.getByLabelText("Go to next page"));

        await waitFor(() => expect(mocks.api.failedProvisioningRequests).toHaveBeenLastCalledWith({
            page: 1,
            size: 20,
            state: "OPEN"
        }));
    });

    it("shows the closed archive with actor, reason and date but no retry or close actions", async () => {
        const closedRequest = {
            ...failedRequest,
            closedAt: "2026-09-23T10:00:00Z",
            closedBy: "manager-1",
            closureReason: "The original role was deleted permanently."
        };
        mocks.api.failedProvisioningRequests.mockImplementation(({ state }) => Promise.resolve(state === "CLOSED"
            ? { items: [closedRequest], page: 0, size: 20, total: 1 }
            : { items: [failedRequest], page: 0, size: 20, total: 1 }));

        renderPage();
        await screen.findByText("Finance Reader");
        fireEvent.click(screen.getByRole("tab", { name: "Closed failures" }));

        expect(await screen.findByText("The original role was deleted permanently.")).toBeInTheDocument();
        expect(screen.getByText("manager-1")).toBeInTheDocument();
        expect(screen.getByText("Closed at")).toBeInTheDocument();
        expect(screen.getByText("Closed by")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Retry provisioning" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Close failure" })).not.toBeInTheDocument();
        expect(mocks.api.failedProvisioningRequests).toHaveBeenLastCalledWith({ page: 0, size: 20, state: "CLOSED" });
    });

    it("shows an archive-specific empty state and keeps pagination scoped to closures", async () => {
        mocks.api.failedProvisioningRequests.mockImplementation(({ state, page }) => Promise.resolve(state === "CLOSED"
            ? { items: page === 0 ? [failedRequest] : [], page, size: 20, total: 21 }
            : { items: [], page: 0, size: 20, total: 0 }));
        renderPage();
        fireEvent.click(screen.getByRole("tab", { name: "Closed failures" }));
        await screen.findByText("request-1");
        fireEvent.click(screen.getByLabelText("Go to next page"));
        await waitFor(() => expect(mocks.api.failedProvisioningRequests)
            .toHaveBeenLastCalledWith({ page: 1, size: 20, state: "CLOSED" }));

        mocks.api.failedProvisioningRequests.mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
        fireEvent.click(screen.getByRole("tab", { name: "Open failures" }));
        fireEvent.click(screen.getByRole("tab", { name: "Closed failures" }));
        expect(await screen.findByText("No closed provisioning failures.")).toBeInTheDocument();
    });

    it("requires confirmation, prevents duplicate retries, then refreshes the list after success", async () => {
        let finishRetry!: (value: unknown) => void;
        mocks.api.retryFailedProvisioning.mockReturnValue(new Promise((resolve) => {
            finishRetry = resolve;
        }));
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 })
            .mockResolvedValueOnce({ items: [], page: 0, size: 20, total: 0 });

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Retry provisioning" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry provisioning" });
        const confirm = within(dialog).getByRole("button", { name: "Retry provisioning" });
        fireEvent.click(confirm);
        fireEvent.click(confirm);

        await waitFor(() => expect(mocks.api.retryFailedProvisioning).toHaveBeenCalledOnce());
        expect(mocks.api.retryFailedProvisioning).toHaveBeenCalledWith("request-1");
        finishRetry({
            decisionStatus: "APPROVED",
            entitlementId: "finance-reader",
            id: "request-1",
            provisioningStatus: "SUCCEEDED"
        });

        expect(await screen.findByText("Provisioning retry completed.")).toBeInTheDocument();
        expect(await screen.findByText("No failed provisioning requests.")).toBeInTheDocument();
        expect(mocks.api.failedProvisioningRequests).toHaveBeenCalledTimes(2);
    });

    it("keeps the confirmation open and presents a safe conflict message when another action wins", async () => {
        mocks.api.retryFailedProvisioning.mockRejectedValue(Object.assign(
            new Error("The access request API call failed."),
            { code: "INVALID_PROVISIONING_RETRY", message: "Sensitive provisioning internals.", status: 409 }
        ));

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Retry provisioning" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry provisioning" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry provisioning" }));

        expect(await within(dialog).findByText("The request changed or is no longer eligible for retry.")).toBeInTheDocument();
        expect(within(dialog).queryByText("Sensitive provisioning internals.")).not.toBeInTheDocument();
    });

    it("does not report success when provisioning fails again", async () => {
        mocks.api.retryFailedProvisioning.mockResolvedValue({
            decisionStatus: "APPROVED",
            entitlementId: "finance-reader",
            id: "request-1",
            provisioningStatus: "FAILED"
        });

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Retry provisioning" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry provisioning" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry provisioning" }));

        expect(await screen.findByText("Provisioning failed again.")).toBeInTheDocument();
        expect(screen.queryByText("Provisioning retry completed.")).not.toBeInTheDocument();
        await waitFor(() => expect(mocks.api.failedProvisioningRequests).toHaveBeenCalledTimes(2));
        expect(screen.getByRole("button", { name: "Retry provisioning" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Close failure" })).toBeInTheDocument();
    });

    it("hides the old diagnosis until a failed retry can be refreshed", async () => {
        mocks.api.retryFailedProvisioning.mockResolvedValue({
            decisionStatus: "APPROVED",
            entitlementId: "finance-reader",
            id: "request-1",
            provisioningStatus: "FAILED"
        });
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(new TypeError("Failed to fetch"))
            .mockResolvedValueOnce({
                items: [{ ...failedRequest, failureCode: "REQUESTER_MISSING" }],
                page: 0,
                size: 20,
                total: 1
            });

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Retry provisioning" }));
        fireEvent.click(within(await screen.findByRole("dialog", { name: "Retry provisioning" }))
            .getByRole("button", { name: "Retry provisioning" }));

        expect(await screen.findByText("The service is unavailable.")).toBeInTheDocument();
        expect(screen.getByText("Provisioning failed again.")).toBeInTheDocument();
        expect(screen.getByText("The cause is unavailable.")).toBeInTheDocument();
        expect(screen.queryByText("The original resource is missing.")).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "Reload" }));
        expect(await screen.findByText("The requester no longer exists.")).toBeInTheDocument();
        expect(screen.queryByText("The cause is unavailable.")).not.toBeInTheDocument();
    });

    it("ignores a diagnosis fetched before the failed retry completed", async () => {
        let finishStaleFetch!: (value: unknown) => void;
        mocks.api.retryFailedProvisioning.mockResolvedValue({
            decisionStatus: "APPROVED",
            entitlementId: "finance-reader",
            id: "request-1",
            provisioningStatus: "FAILED"
        });
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 21 })
            .mockReturnValueOnce(new Promise((resolve) => { finishStaleFetch = resolve; }))
            .mockRejectedValueOnce(new TypeError("Failed to fetch"));

        renderPage();
        await screen.findByText("The original resource is missing.");
        fireEvent.click(screen.getByLabelText("Go to next page"));
        await waitFor(() => expect(mocks.api.failedProvisioningRequests).toHaveBeenCalledTimes(2));

        fireEvent.click(screen.getByRole("button", { name: "Retry provisioning" }));
        fireEvent.click(within(await screen.findByRole("dialog", { name: "Retry provisioning" }))
            .getByRole("button", { name: "Retry provisioning" }));
        expect(await screen.findByText("The cause is unavailable.")).toBeInTheDocument();

        finishStaleFetch({ items: [failedRequest], page: 1, size: 20, total: 21 });
        expect(await screen.findByText("The service is unavailable.")).toBeInTheDocument();
        expect(screen.getByText("The cause is unavailable.")).toBeInTheDocument();
        expect(screen.queryByText("The original resource is missing.")).not.toBeInTheDocument();
    });

    it("removes a successfully retried request before refresh, even if reloading fails", async () => {
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(new TypeError("Failed to fetch"));

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Retry provisioning" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry provisioning" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry provisioning" }));

        expect(await screen.findByText("Provisioning retry completed.")).toBeInTheDocument();
        expect(await screen.findByText("The service is unavailable.")).toBeInTheDocument();
        expect(screen.getByText("No failed provisioning requests.")).toBeInTheDocument();
        expect(screen.queryByText("request-1")).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Retry provisioning" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Close failure" })).not.toBeInTheDocument();
    });

    it("does not reintroduce a recovered request from a stale refresh response", async () => {
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 })
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 });

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Retry provisioning" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry provisioning" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry provisioning" }));

        await waitFor(() => expect(mocks.api.failedProvisioningRequests).toHaveBeenCalledTimes(2));
        expect(await screen.findByText("No failed provisioning requests.")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Retry provisioning" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Close failure" })).not.toBeInTheDocument();
    });

    it("requires a reason and closes without duplicate submissions, then refreshes the queue", async () => {
        let finishClosure!: (value: unknown) => void;
        mocks.api.closeFailedProvisioning.mockReturnValue(new Promise((resolve) => { finishClosure = resolve; }));
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 })
            .mockResolvedValueOnce({ items: [], page: 0, size: 20, total: 0 });

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Close failure" }));
        const dialog = await screen.findByRole("dialog", { name: "Close failure" });
        const confirm = within(dialog).getByRole("button", { name: "Close failure" });
        expect(confirm).toBeDisabled();
        fireEvent.change(within(dialog).getByLabelText(/Closure reason/), { target: { value: "Too short" } });
        expect(confirm).toBeDisabled();
        fireEvent.change(within(dialog).getByLabelText(/Closure reason/), {
            target: { value: "The original role was deleted permanently." }
        });
        fireEvent.click(confirm);
        fireEvent.click(confirm);
        await waitFor(() => expect(mocks.api.closeFailedProvisioning).toHaveBeenCalledOnce());
        expect(mocks.api.closeFailedProvisioning).toHaveBeenCalledWith(
            "request-1", "The original role was deleted permanently."
        );
        finishClosure({ id: "request-1", provisioningStatus: "FAILED" });
        expect(await screen.findByText("Failure closed.")).toBeInTheDocument();
        expect(await screen.findByText("No failed provisioning requests.")).toBeInTheDocument();
    });

    it("keeps a confirmed closure out of the actionable list when refresh fails", async () => {
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(new TypeError("Failed to fetch"))
            .mockResolvedValueOnce({
                items: [{
                    ...failedRequest,
                    closedAt: "2026-09-23T10:00:00Z",
                    closedBy: "manager-1",
                    closureReason: "The original role was deleted permanently."
                }],
                page: 0, size: 20, total: 1
            });

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Close failure" }));
        const dialog = await screen.findByRole("dialog", { name: "Close failure" });
        fireEvent.change(within(dialog).getByLabelText(/Closure reason/), {
            target: { value: "The original role was deleted permanently." }
        });
        fireEvent.click(within(dialog).getByRole("button", { name: "Close failure" }));

        expect(await screen.findByText("Failure closed.")).toBeInTheDocument();
        expect(await screen.findByText("The service is unavailable.")).toBeInTheDocument();
        expect(screen.getByText("No failed provisioning requests.")).toBeInTheDocument();
        expect(screen.queryByText("request-1")).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Retry provisioning" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Close failure" })).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole("tab", { name: "Closed failures" }));
        expect(await screen.findByText("The original role was deleted permanently.")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Retry provisioning" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Close failure" })).not.toBeInTheDocument();
    });

    it("does not reintroduce a closed request from a stale refresh response", async () => {
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 })
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 });

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Close failure" }));
        const dialog = await screen.findByRole("dialog", { name: "Close failure" });
        fireEvent.change(within(dialog).getByLabelText(/Closure reason/), {
            target: { value: "The original role was deleted permanently." }
        });
        fireEvent.click(within(dialog).getByRole("button", { name: "Close failure" }));

        await waitFor(() => expect(mocks.api.failedProvisioningRequests).toHaveBeenCalledTimes(2));
        expect(await screen.findByText("No failed provisioning requests.")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Retry provisioning" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Close failure" })).not.toBeInTheDocument();
    });

    it("keeps the closure dialog and reason after a conflicting update", async () => {
        mocks.api.closeFailedProvisioning.mockRejectedValue(Object.assign(
            new Error("Sensitive internal reason"), { code: "INVALID_PROVISIONING_CLOSURE", status: 409 }
        ));
        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Close failure" }));
        const dialog = await screen.findByRole("dialog", { name: "Close failure" });
        fireEvent.change(within(dialog).getByLabelText(/Closure reason/), {
            target: { value: "The requester was deleted from this realm." }
        });
        fireEvent.click(within(dialog).getByRole("button", { name: "Close failure" }));
        expect(await within(dialog).findByText("The request changed or is no longer eligible for retry.")).toBeInTheDocument();
        expect(within(dialog).getByLabelText(/Closure reason/)).toHaveValue("The requester was deleted from this realm.");
        expect(within(dialog).queryByText("Sensitive internal reason")).not.toBeInTheDocument();
    });
});
