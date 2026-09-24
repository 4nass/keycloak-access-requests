import { createInstance } from "i18next";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const adminSourceDirectory = dirname(fileURLToPath(import.meta.url));
const messageDirectory = resolve(
    adminSourceDirectory,
    "../../../../src/main/resources/theme/access-requests/admin/messages"
);

async function readMessages(locale: string) {
    const messageBundle = new TextDecoder("utf-8", { fatal: true }).decode(
        await readFile(resolve(messageDirectory, `messages_${locale}.properties`))
    );
    return Object.fromEntries(
        messageBundle
        .split(/\r?\n/)
        .filter((line) => line && !line.startsWith("#"))
        .map((line) => {
            const separator = line.indexOf("=");
            return [line.slice(0, separator), line.slice(separator + 1)];
        })
    );
}

const messages = await readMessages("en");
const translatedMessages = await Promise.all(
    ["fr", "de", "es"].map(async (locale) => [locale, await readMessages(locale)] as const)
);

const expectedMessageKeys = [
    "access-requests",
    "accessRequestsAdminApproverRole",
    "accessRequestsAdminCancel",
    "accessRequestsAdminClosedToRequests",
    "accessRequestsAdminCatalog",
    "accessRequestsAdminCatalogDescription",
    "accessRequestsAdminCreated",
    "accessRequestsAdminCreateEntitlement",
    "accessRequestsAdminDescription",
    "accessRequestsAdminDisplayName",
    "accessRequestsAdminEditEntitlement",
    "accessRequestsAdminEvents",
    "accessRequestsAdminEventsDescription",
    "accessRequestsAdminEventsEmpty",
    "accessRequestsAdminEventsFrom",
    "accessRequestsAdminEventsTo",
    "accessRequestsAdminEventsType",
    "accessRequestsAdminEventsActor",
    "accessRequestsAdminEventsRequest",
    "accessRequestsAdminEventsOccurredAt",
    "accessRequestsAdminEventsViewRequest",
    "accessRequestsAdminEventsAllTypes",
    "accessRequestsAdminEventsJustification",
    "accessRequestsAdminEventsHistory",
    "accessRequestsAdminEventRequestCreated",
    "accessRequestsAdminEventRequestCanceled",
    "accessRequestsAdminEventRequestApproved",
    "accessRequestsAdminEventRequestRejected",
    "accessRequestsAdminEventProvisioningStarted",
    "accessRequestsAdminEventProvisioningSucceeded",
    "accessRequestsAdminEventProvisioningFailed",
    "accessRequestsAdminEventProvisioningClosed",
    "accessRequestsAdminEmpty",
    "accessRequestsAdminErrorConflict",
    "accessRequestsAdminErrorForbidden",
    "accessRequestsAdminErrorInvalidRequest",
    "accessRequestsAdminErrorNotFound",
    "accessRequestsAdminErrorUnauthorized",
    "accessRequestsAdminErrorUnavailable",
    "accessRequestsAdminErrorUnexpected",
    "accessRequestsAdminFailedProvisioning",
    "accessRequestsAdminFailedProvisioningOpen",
    "accessRequestsAdminFailedProvisioningClosed",
    "accessRequestsAdminFailedProvisioningClosedEmpty",
    "accessRequestsAdminFailedProvisioningClosedAt",
    "accessRequestsAdminFailedProvisioningClosedBy",
    "accessRequestsAdminFailedProvisioningClose",
    "accessRequestsAdminFailedProvisioningCloseDescription",
    "accessRequestsAdminFailedProvisioningCloseReason",
    "accessRequestsAdminFailedProvisioningCloseSuccess",
    "accessRequestsAdminFailedProvisioningDescription",
    "accessRequestsAdminFailedProvisioningEmpty",
    "accessRequestsAdminFailedProvisioningEntitlement",
    "accessRequestsAdminFailedProvisioningRequester",
    "accessRequestsAdminFailedProvisioningResource",
    "accessRequestsAdminFailedProvisioningRetry",
    "accessRequestsAdminFailedProvisioningRetryDescription",
    "accessRequestsAdminFailedProvisioningRetrySuccess",
    "accessRequestsAdminFailedProvisioningRetryStillFailed",
    "accessRequestsAdminFailedProvisioningStatus",
    "accessRequestsAdminFailureCause",
    "accessRequestsAdminFailureRequesterMissing",
    "accessRequestsAdminFailureResourceMissing",
    "accessRequestsAdminFailureResourceTypeMismatch",
    "accessRequestsAdminFailureRealmMismatch",
    "accessRequestsAdminFailureProviderUnavailable",
    "accessRequestsAdminFailureUnexpected",
    "accessRequestsAdminFailureUnknown",
    "accessRequestsAdminLoadError",
    "accessRequestsAdminNotAvailable",
    "accessRequestsAdminOpenToRequests",
    "accessRequestsAdminProvisioningFailed",
    "accessRequestsAdminNotificationDelivery",
    "accessRequestsAdminNotificationDeliveryAttempts",
    "accessRequestsAdminNotificationDeliveryDelivered",
    "accessRequestsAdminNotificationDeliveryDescription",
    "accessRequestsAdminNotificationDeliveryDiscarded",
    "accessRequestsAdminNotificationDeliveryEmpty",
    "accessRequestsAdminNotificationDeliveryEntitlementId",
    "accessRequestsAdminNotificationDeliveryFailed",
    "accessRequestsAdminNotificationDeliveryLastAttempt",
    "accessRequestsAdminNotificationDeliveryPending",
    "accessRequestsAdminNotificationDeliveryProcessing",
    "accessRequestsAdminNotificationDeliveryRecipient",
    "accessRequestsAdminNotificationDeliveryRecipientType",
    "accessRequestsAdminNotificationDeliveryRequestId",
    "accessRequestsAdminNotificationDeliveryRetry",
    "accessRequestsAdminNotificationDeliveryRetryDescription",
    "accessRequestsAdminNotificationDeliveryRetryQueued",
    "accessRequestsAdminNotificationDeliverySummary",
    "accessRequestsAdminNotificationTypeProvisioningFailed",
    "accessRequestsAdminNotificationTypeProvisioningClosed",
    "accessRequestsAdminNotificationTypeRequestApproved",
    "accessRequestsAdminNotificationTypeRequestRejected",
    "accessRequestsAdminNotificationTypeRequestSubmitted",
    "accessRequestsAdminNotificationRecipientTypeRealmRole",
    "accessRequestsAdminNotificationRecipientTypeUser",
    "accessRequestsAdminReferencesEmpty",
    "accessRequestsAdminReferencesLoading",
    "accessRequestsAdminRequestable",
    "accessRequestsAdminResourceId",
    "accessRequestsAdminResourceType",
    "accessRequestsAdminResourceTypeClientRole",
    "accessRequestsAdminResourceTypeGroup",
    "accessRequestsAdminResourceTypeRealmRole",
    "accessRequestsAdminRiskLevel",
    "accessRequestsAdminRiskLevelCritical",
    "accessRequestsAdminRiskLevelHigh",
    "accessRequestsAdminRiskLevelLow",
    "accessRequestsAdminRiskLevelMedium",
    "accessRequestsAdminSave",
    "accessRequestsAdminSearchApproverRoles",
    "accessRequestsAdminSearchApproverRolesPlaceholder",
    "accessRequestsAdminSearchResources",
    "accessRequestsAdminSearchResourcesPlaceholder",
    "accessRequestsAdminSelectApproverRole",
    "accessRequestsAdminSelectResource",
    "accessRequestsAdminUpdated",
    "accessRequestsAdminVersion"
];

const featureSourcePaths = [
    "AccessRequestsAdminApp.tsx",
    "AccessRequestsAdminPageNav.tsx",
    "api/EntitlementsAdminApi.ts",
    "api/useEntitlementsAdminApi.ts",
    "environment.ts",
    "i18n.ts",
    "main.tsx",
    "pages/EntitlementCatalogPage.tsx",
    "pages/EntitlementCatalogRoute.tsx",
    "pages/AccessRequestsAdminTabs.tsx",
    "pages/AuditEventsPage.tsx",
    "pages/AuditEventsRoute.tsx",
    "pages/AuditRequestDetailsPage.tsx",
    "pages/AuditRequestDetailsRoute.tsx",
    "pages/AuthorizedAuditPage.tsx",
    "pages/auditEventTypes.ts",
    "pages/FailedProvisioningPage.tsx",
    "pages/FailedProvisioningRoute.tsx",
    "pages/NotificationDeliveryPage.tsx",
    "pages/NotificationDeliveryRoute.tsx",
    "routes.tsx"
];

async function referencedAdminMessageKeys() {
    const source = await Promise.all(
        featureSourcePaths.map((path) => readFile(resolve(adminSourceDirectory, path), "utf8"))
    );

    return new Set(
        source.flatMap((content) => Array.from(content.matchAll(/["'](accessRequestsAdmin[A-Za-z0-9]+)["']/g), (match) => match[1]))
    );
}

describe("Access Request Admin Console translations", () => {
    it("ships the complete, non-empty English message bundle", () => {
        expect(Object.keys(messages).sort()).toEqual(expectedMessageKeys.sort());
        expect(Object.values(messages).every((message) => message.trim().length > 0)).toBe(true);
        expect(Object.values(messages).every((message) => !message.includes("''"))).toBe(true);
    });

    it("keeps every supported Admin Console locale complete", () => {
        translatedMessages.forEach(([locale, translated]) => {
            expect(Object.keys(translated).sort(), locale).toEqual(Object.keys(messages).sort());
            expect(Object.values(translated).every((message) => message.trim().length > 0), locale).toBe(true);
            expect(Object.values(translated).every((message) => !message.includes("''")), locale).toBe(true);
        });
    });

    it("ships a translation for every feature key referenced by the Admin Console", async () => {
        const missingKeys = [...await referencedAdminMessageKeys()]
            .filter((key) => !messages[key] || messages[key].trim().length === 0)
            .sort();

        expect(missingKeys).toEqual([]);
    });

    it("falls back to English when Chinese has no theme bundle", async () => {
        const i18n = createInstance();
        await i18n.init({
            fallbackLng: "en",
            lng: "zh-CN",
            resources: {
                en: {
                    translation: messages
                }
            }
        });

        expect(i18n.t("accessRequestsAdminCatalog")).toBe("Access requests");
        expect(i18n.t("accessRequestsAdminErrorForbidden"))
            .toBe("You do not have permission to manage access requests in this realm.");
    });
});
