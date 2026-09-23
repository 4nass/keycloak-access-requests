import { createInstance } from "i18next";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
    api: {
        failedProvisioningRequests: vi.fn(),
        retryFailedProvisioning: vi.fn()
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
                accessRequestsAdminFailedProvisioningDescription: "Review approved requests that could not be provisioned.",
                accessRequestsAdminFailedProvisioningEmpty: "No failed provisioning requests.",
                accessRequestsAdminFailedProvisioningEntitlement: "Entitlement",
                accessRequestsAdminFailedProvisioningRequester: "Requester",
                accessRequestsAdminFailedProvisioningResource: "Resource",
                accessRequestsAdminFailedProvisioningRetry: "Retry provisioning",
                accessRequestsAdminFailedProvisioningRetryDescription: "Retry granting the approved entitlement.",
                accessRequestsAdminFailedProvisioningRetrySuccess: "Provisioning retry completed.",
                accessRequestsAdminFailedProvisioningStatus: "Provisioning status",
                accessRequestsAdminProvisioningFailed: "Provisioning failed",
                accessRequestsAdminNotAvailable: "Not available",
                close: "Close",
                loading: "Loading"
            }
        }
    }
});

const failedRequest = {
    decisionStatus: "APPROVED" as const,
    entitlementId: "finance-reader",
    id: "request-1",
    provisioningStatus: "FAILED" as const,
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
    });

    it("renders paginated failed request metadata without requester justification or internal failure details", async () => {
        renderPage();

        expect(await screen.findByText("Finance Reader")).toBeInTheDocument();
        expect(screen.getByText("user-1")).toBeInTheDocument();
        expect(screen.getByText("request-1")).toBeInTheDocument();
        expect(screen.getByText("Provisioning failed")).toBeInTheDocument();
        expect(screen.queryByText(/failureReason|justification|stack trace/i)).not.toBeInTheDocument();
        expect(mocks.api.failedProvisioningRequests).toHaveBeenCalledWith({ page: 0, size: 20 });
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
            size: 20
        }));
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

    it("preserves the page data and shows a refresh error if reloading fails after a successful retry", async () => {
        mocks.api.failedProvisioningRequests
            .mockResolvedValueOnce({ items: [failedRequest], page: 0, size: 20, total: 1 })
            .mockRejectedValueOnce(new TypeError("Failed to fetch"));

        renderPage();
        fireEvent.click(await screen.findByRole("button", { name: "Retry provisioning" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry provisioning" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry provisioning" }));

        expect(await screen.findByText("Provisioning retry completed.")).toBeInTheDocument();
        expect(await screen.findByText("The service is unavailable.")).toBeInTheDocument();
        expect(screen.getByText("request-1")).toBeInTheDocument();
    });
});
