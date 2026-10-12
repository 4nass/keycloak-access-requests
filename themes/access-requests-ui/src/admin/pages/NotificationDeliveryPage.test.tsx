import { createInstance } from "i18next";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
    api: {
        notificationDeliveries: vi.fn(),
        notificationDeliverySummary: vi.fn(),
        retryNotificationDelivery: vi.fn()
    }
}));

vi.mock("../api/useEntitlementsAdminApi", () => ({
    useEntitlementsAdminApi: () => mocks.api
}));

import { NotificationDeliveryPage } from "./NotificationDeliveryPage";

const i18n = createInstance();

await i18n.init({
    initImmediate: false,
    lng: "en",
    resources: {
        en: {
            translation: {
                accessRequestsAdminCancel: "Cancel",
                accessRequestsAdminErrorConflict: "The delivery was changed by another administrator.",
                accessRequestsAdminErrorForbidden: "You do not have permission.",
                accessRequestsAdminErrorInvalidRequest: "The delivery is invalid.",
                accessRequestsAdminErrorNotFound: "The delivery no longer exists.",
                accessRequestsAdminErrorUnauthorized: "Your session is no longer authorized.",
                accessRequestsAdminErrorUnavailable: "The service is unavailable.",
                accessRequestsAdminErrorUnexpected: "The action could not be completed.",
                accessRequestsAdminNotAvailable: "Not available",
                accessRequestsAdminNotificationDelivery: "Notification failures",
                accessRequestsAdminNotificationDeliveryAttempts: "Attempts",
                accessRequestsAdminNotificationDeliveryDescription: "Review and retry failed access-request notifications.",
                accessRequestsAdminNotificationDeliveryEmpty: "There are no failed notifications.",
                accessRequestsAdminNotificationDeliveryEntitlementId: "Entitlement ID",
                accessRequestsAdminNotificationDeliveryFailed: "Failed notifications",
                accessRequestsAdminNotificationDeliveryFailedStatus: "Failed",
                accessRequestsAdminNotificationDeliveryLastAttempt: "Last attempt",
                accessRequestsAdminNotificationDeliveryRecipient: "Recipient",
                accessRequestsAdminNotificationDeliveryRecipientType: "Recipient type",
                accessRequestsAdminNotificationDeliveryRequestId: "Request",
                accessRequestsAdminNotificationDeliveryRetry: "Retry notification",
                accessRequestsAdminNotificationDeliveryRetryDescription: "The notification will return to the queue.",
                accessRequestsAdminNotificationDeliveryRetryQueued: "Notification queued for retry.",
                accessRequestsAdminNotificationTypeProvisioningFailed: "Provisioning failed",
                accessRequestsAdminNotificationRecipientTypeRealmRole: "Realm role",
                accessRequestsAdminNotificationRecipientTypeUser: "User",
                accessRequestsAdminNotificationTypeRequestApproved: "Request approved",
                accessRequestsAdminNotificationTypeRequestRejected: "Request rejected",
                accessRequestsAdminNotificationTypeRequestSubmitted: "Request submitted",
                close: "Close",
                loading: "Loading"
            }
        }
    }
});

const failedDelivery = {
    attemptCount: 10,
    entitlementId: "entitlement-1",
    entitlementName: "Finance access",
    id: "delivery-1",
    lastAttemptAt: "2026-09-22T10:00:00Z",
    notificationType: "REQUEST_SUBMITTED" as const,
    recipientId: "user-1",
    recipientName: "Alex Reader",
    recipientType: "USER" as const,
    requestId: "request-1",
    requestName: "Finance access"
};

function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((onResolve) => {
        resolve = onResolve;
    });
    return { promise, resolve };
}

function renderPage() {
    return render(
        <I18nextProvider i18n={i18n}>
            <NotificationDeliveryPage />
        </I18nextProvider>
    );
}

describe("NotificationDeliveryPage", () => {
    beforeEach(() => {
        mocks.api.notificationDeliveries.mockReset().mockResolvedValue({
            items: [failedDelivery],
            page: 0,
            size: 20,
            total: 1
        });
        mocks.api.notificationDeliverySummary.mockReset().mockResolvedValue({
            discarded: 2,
            delivered: 8,
            failed: 1,
            pending: 3,
            processing: 1
        });
        mocks.api.retryNotificationDelivery.mockReset().mockResolvedValue(undefined);
    });

    it("focuses on actionable failed deliveries without displaying misleading email totals", async () => {
        renderPage();

        expect(await screen.findByText("Request submitted")).toBeInTheDocument();
        expect(screen.getByText("10")).toBeInTheDocument();
        expect(screen.getByText("Failed")).toBeInTheDocument();
        expect(screen.queryByRole("group", { name: "Delivery status" })).not.toBeInTheDocument();
        expect(mocks.api.notificationDeliverySummary).not.toHaveBeenCalled();

        fireEvent.click(screen.getByRole("button", { name: "Retry notification" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry notification" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry notification" }));

        await waitFor(() => expect(mocks.api.retryNotificationDelivery).toHaveBeenCalledWith("delivery-1"));
        expect(await screen.findByText("Notification queued for retry.")).toBeInTheDocument();
        await waitFor(() => expect(mocks.api.notificationDeliveries).toHaveBeenCalledTimes(2));
    });

    it("treats a failed recipient-role expansion as a notification failure, not a sent email", async () => {
        mocks.api.notificationDeliveries.mockResolvedValue({
            items: [{ ...failedDelivery, recipientId: "role-1", recipientName: "Approvers", recipientType: "REALM_ROLE" }],
            page: 0,
            size: 20,
            total: 1
        });
        renderPage();

        expect(await screen.findByText("Realm role")).toBeInTheDocument();
        expect(screen.getByText("Failed")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Retry notification" })).toBeInTheDocument();
        expect(screen.queryByText("Failed emails")).not.toBeInTheDocument();
    });

    it("keeps the newest delivery page when an older response finishes last", async () => {
        const olderDeliveries = deferred<{ items: typeof failedDelivery[]; total: number }>();
        mocks.api.notificationDeliveries.mockReset()
            .mockResolvedValueOnce({ items: [failedDelivery], total: 40 })
            .mockReturnValueOnce(olderDeliveries.promise)
            .mockResolvedValueOnce({ items: [{ ...failedDelivery, id: "delivery-current", requestId: "request-current",
                requestName: "Current access" }], total: 40 });
        renderPage();

        await screen.findByText("Request: Finance access");
        fireEvent.click(screen.getAllByRole("button", { name: "Go to next page" })[0]);
        await waitFor(() => expect(mocks.api.notificationDeliveries).toHaveBeenCalledWith({ page: 1, size: 20 }));
        fireEvent.click(screen.getAllByRole("button", { name: "Go to previous page" })[0]);
        expect(await screen.findByText("Request: Current access")).toBeVisible();

        await act(async () => {
            olderDeliveries.resolve({ items: [{ ...failedDelivery, id: "delivery-stale", requestId: "request-stale" }], total: 40 });
        });
        expect(screen.getByText("Request: Current access")).toBeVisible();
        expect(screen.queryByText("Request: Stale access")).not.toBeInTheDocument();
        expect(mocks.api.notificationDeliverySummary).not.toHaveBeenCalled();
    });

    it("hides previous-page deliveries and retry actions while the next page loads", async () => {
        const nextPage = deferred<{ items: typeof failedDelivery[]; total: number }>();
        mocks.api.notificationDeliveries.mockReset()
            .mockResolvedValueOnce({ items: [failedDelivery], total: 40 })
            .mockReturnValueOnce(nextPage.promise);
        renderPage();

        await screen.findByText("Request: Finance access");
        fireEvent.click(screen.getAllByRole("button", { name: "Go to next page" })[0]);
        await waitFor(() => expect(mocks.api.notificationDeliveries).toHaveBeenCalledWith({ page: 1, size: 20 }));
        expect(screen.queryByText("Request: Finance access")).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Retry notification" })).not.toBeInTheDocument();
        expect(screen.getByLabelText("Loading")).toBeVisible();

        await act(async () => nextPage.resolve({
            items: [{ ...failedDelivery, id: "delivery-2", requestId: "request-2",
                requestName: "Next access" }], total: 40
        }));
        expect(screen.getByText("Request: Next access")).toBeVisible();
    });

    it("keeps the confirmation dialog open and presents a safe API error when the retry is rejected", async () => {
        mocks.api.retryNotificationDelivery.mockRejectedValue(Object.assign(
            new Error("The access request API call failed."),
            { code: "NOTIFICATION_DELIVERY_NOT_FAILED", status: 409 }
        ));
        renderPage();

        fireEvent.click(await screen.findByRole("button", { name: "Retry notification" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry notification" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry notification" }));

        expect(await within(dialog).findByText("The delivery was changed by another administrator.")).toBeInTheDocument();
    });
});
