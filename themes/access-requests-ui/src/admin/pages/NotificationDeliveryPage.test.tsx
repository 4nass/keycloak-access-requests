import { createInstance } from "i18next";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
                accessRequestsAdminNotificationDelivery: "Notification delivery",
                accessRequestsAdminNotificationDeliveryAttempts: "Attempts",
                accessRequestsAdminNotificationDeliveryDelivered: "Delivered",
                accessRequestsAdminNotificationDeliveryDescription: "Monitor delivery.",
                accessRequestsAdminNotificationDeliveryDiscarded: "Discarded",
                accessRequestsAdminNotificationDeliveryEmpty: "No failed deliveries.",
                accessRequestsAdminNotificationDeliveryEntitlementId: "Entitlement ID",
                accessRequestsAdminNotificationDeliveryFailed: "Failed deliveries",
                accessRequestsAdminNotificationDeliveryLastAttempt: "Last attempt",
                accessRequestsAdminNotificationDeliveryPending: "Pending",
                accessRequestsAdminNotificationDeliveryProcessing: "Processing",
                accessRequestsAdminNotificationDeliveryRecipient: "Recipient",
                accessRequestsAdminNotificationDeliveryRecipientType: "Recipient type",
                accessRequestsAdminNotificationDeliveryRequestId: "Request ID",
                accessRequestsAdminNotificationDeliveryRetry: "Retry delivery",
                accessRequestsAdminNotificationDeliveryRetryDescription: "The delivery will return to the queue.",
                accessRequestsAdminNotificationDeliveryRetryQueued: "Notification delivery queued for retry.",
                accessRequestsAdminNotificationDeliverySummary: "Delivery status",
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
    id: "delivery-1",
    lastAttemptAt: "2026-09-22T10:00:00Z",
    notificationType: "REQUEST_SUBMITTED" as const,
    recipientId: "user-1",
    recipientType: "USER" as const,
    requestId: "request-1"
};

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

    it("shows operational counts, preserves the failed delivery metadata, and requeues after confirmation", async () => {
        renderPage();

        expect(await screen.findByText("Request submitted")).toBeInTheDocument();
        expect(screen.getByText("3")).toBeInTheDocument();
        expect(screen.getByText("10")).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "Retry delivery" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry delivery" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry delivery" }));

        await waitFor(() => expect(mocks.api.retryNotificationDelivery).toHaveBeenCalledWith("delivery-1"));
        expect(await screen.findByText("Notification delivery queued for retry.")).toBeInTheDocument();
        await waitFor(() => expect(mocks.api.notificationDeliveries).toHaveBeenCalledTimes(2));
    });

    it("keeps the confirmation dialog open and presents a safe API error when the retry is rejected", async () => {
        mocks.api.retryNotificationDelivery.mockRejectedValue(Object.assign(
            new Error("The access request API call failed."),
            { code: "NOTIFICATION_DELIVERY_NOT_FAILED", status: 409 }
        ));
        renderPage();

        fireEvent.click(await screen.findByRole("button", { name: "Retry delivery" }));
        const dialog = await screen.findByRole("dialog", { name: "Retry delivery" });
        fireEvent.click(within(dialog).getByRole("button", { name: "Retry delivery" }));

        expect(await within(dialog).findByText("The delivery was changed by another administrator.")).toBeInTheDocument();
    });
});
