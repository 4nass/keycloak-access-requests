import { createInstance } from "i18next";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { durationText } from "./pages/catalogDuration";

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
    "accessRequestsAdminAllowPermanent",
    "accessRequestsAdminAssuranceAcr",
    "accessRequestsAdminAssuranceAgeSeconds",
    "accessRequestsAdminAssuranceDescription",
    "accessRequestsAdminAssuranceLoa",
    "accessRequestsAdminAssuranceMaxAgeWarning",
    "accessRequestsAdminAssuranceMfaWarning",
    "accessRequestsAdminAssurancePolicy",
    "accessRequestsAdminAssuranceSaved",
    "accessRequestsAdminCancel",
    "accessRequestsAdminClosedToRequests",
    "accessRequestsAdminCatalog",
    "accessRequestsAdminCatalogTab",
    "accessRequestsAdminEventsRequestTitle",
    "accessRequestsAdminEventsClose",
    "accessRequestsAdminSystemActor",
    "accessRequestsAdminUserUnavailable",
    "accessRequestsAdminCatalogDescription",
    "accessRequestsAdminLastUpdated",
    "accessRequestsAdminCreated",
    "accessRequestsAdminCreateEntitlement",
    "accessRequestsAdminPackageCreate",
    "accessRequestsAdminPackageCreateDescription",
    "accessRequestsAdminPackageCreated",
    "accessRequestsAdminPackageDetails",
    "accessRequestsAdminPackageAccessWarning",
    "accessRequestsAdminPackageRoles",
    "accessRequestsAdminPackageMissingGroup",
    "accessRequestsAdminPackageInvalidConfiguration",
    "accessRequestsAdminPackageMissingRole",
    "accessRequestsAdminPackageSelectRoleFirst",
    "accessRequestsAdminPackageDuplicateRole",
    "accessRequestsAdminPackageRoleLimit",
    "accessRequestsAdminPackageAtLeastOneRole",
    "accessRequestsAdminPackageAddRole",
    "accessRequestsAdminPackageSearchRoles",
    "accessRequestsAdminPackageSelectRole",
    "accessRequestsAdminPackageSelectedRoles",
    "accessRequestsAdminPackageRemoveRole",
    "accessRequestsAdminPackageEditRoles",
    "accessRequestsAdminPackageEditRolesDescription",
    "accessRequestsAdminPackageRolesLocked",
    "accessRequestsAdminPackageRolesUpdated",
    "accessRequestsAdminRemoveFromCatalog",
    "accessRequestsAdminRemoveFromCatalogDescription",
    "accessRequestsAdminRemovedFromCatalog",
    "accessRequestsAdminGroupAccessWarning",
    "accessRequestsAdminDescription",
    "accessRequestsAdminDefaultDuration",
    "accessRequestsAdminDisplayName",
    "accessRequestsAdminDurationUnit",
    "accessRequestsAdminDurationUnitSecond",
    "accessRequestsAdminDurationUnitHour",
    "accessRequestsAdminDurationUnitDay",
    "accessRequestsAdminDurationUnitSeconds",
    "accessRequestsAdminDurationUnitHours",
    "accessRequestsAdminDurationUnitDays",
    "accessRequestsAdminEditEntitlement",
    "accessRequestsAdminEvents",
    "accessRequestsAdminEventsFilters",
    "accessRequestsAdminEventsHideFilters",
    "accessRequestsAdminEventsRequester",
    "accessRequestsAdminEventsSearchRequester",
    "accessRequestsAdminEventsSearchActor",
    "accessRequestsAdminEventsAnyUser",
    "accessRequestsAdminEventsUsersLoading",
    "accessRequestsAdminEventsEnterName",
    "accessRequestsAdminEventsNoUsers",
    "accessRequestsAdminEventsApply",
    "accessRequestsAdminEventsClear",
    "accessRequestsAdminEventsActiveFilters",
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
    "accessRequestsAdminEventsDecisionStatus",
    "accessRequestsAdminEventsProvisioningStatus",
    "accessRequestsAdminDecisionPending",
    "accessRequestsAdminDecisionApproved",
    "accessRequestsAdminDecisionRejected",
    "accessRequestsAdminDecisionCanceled",
    "accessRequestsAdminProvisioningNotStarted",
    "accessRequestsAdminProvisioningSucceeded",
    "accessRequestsAdminProvisioningFailedStatus",
    "accessRequestsAdminEventRequestCreated",
    "accessRequestsAdminEventRequestCanceled",
    "accessRequestsAdminEventRequestApproved",
    "accessRequestsAdminEventRequestRejected",
    "accessRequestsAdminEventProvisioningStarted",
    "accessRequestsAdminEventProvisioningSucceeded",
    "accessRequestsAdminEventProvisioningFailed",
    "accessRequestsAdminEventProvisioningClosed",
    "accessRequestsAdminEventRevocationFailed",
    "accessRequestsAdminEventRevocationSucceeded",
    "accessRequestsAdminEmpty",
    "accessRequestsAdminErrorConflict",
    "accessRequestsAdminErrorForbidden",
    "accessRequestsAdminErrorInvalidRequest",
    "accessRequestsAdminErrorNotFound",
    "accessRequestsAdminErrorUnauthorized",
    "accessRequestsAdminErrorUnavailable",
    "accessRequestsAdminErrorUnexpected",
    "accessRequestsAdminFailedProvisioning",
    "accessRequestsAdminInvalidDuration",
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
    "accessRequestsAdminFailureStatus",
    "accessRequestsAdminFailureOpen",
    "accessRequestsAdminFailureClosed",
    "accessRequestsAdminFailureResolved",
    "accessRequestsAdminFailureRequesterMissing",
    "accessRequestsAdminFailureResourceMissing",
    "accessRequestsAdminFailureResourceTypeMismatch",
    "accessRequestsAdminFailureRealmMismatch",
    "accessRequestsAdminFailureProviderUnavailable",
    "accessRequestsAdminFailureUnexpected",
    "accessRequestsAdminFailureUnknown",
    "accessRequestsAdminGrantExpiry",
    "accessRequestsAdminPermanent",
    "accessRequestsAdminRevokeAccess",
    "accessRequestsAdminRevokeAccessWarning",
    "accessRequestsAdminRevokeAccessSuccess",
    "accessRequestsAdminRevocationFailures",
    "accessRequestsAdminRevocationFailuresDescription",
    "accessRequestsAdminRevocationOpen",
    "accessRequestsAdminRevocationResolved",
    "accessRequestsAdminRevocationOpenEmpty",
    "accessRequestsAdminRevocationResolvedEmpty",
    "accessRequestsAdminRevocationExpiredAt",
    "accessRequestsAdminRevocationAttempts",
    "accessRequestsAdminRevocationFirstFailedAt",
    "accessRequestsAdminRevocationLastFailedAt",
    "accessRequestsAdminRevocationNextAttempt",
    "accessRequestsAdminRevocationManualRetryOnly",
    "accessRequestsAdminRevocationResolvedAt",
    "accessRequestsAdminRevocationRetry",
    "accessRequestsAdminRevocationRetryWarning",
    "accessRequestsAdminRevocationRetrySuccess",
    "accessRequestsAdminRevocationRetryFailed",
    "accessRequestsAdminRevocationDiagnosticPending",
    "accessRequestsAdminRevocationAuthorityUnverifiable",
    "accessRequestsAdminRevocationRemovalFailed",
    "accessRequestsAdminRevocationUnexpectedFailure",
    "accessRequestsAdminRevocationConfirmRemoved",
    "accessRequestsAdminRevocationConfirmRemovedWarning",
    "accessRequestsAdminRevocationResolutionReason",
    "accessRequestsAdminRevocationConfirmedRemoved",
    "accessRequestsAdminLoadError",
    "accessRequestsAdminMaxDuration",
    "accessRequestsAdminNotAvailable",
    "accessRequestsAdminOpenToRequests",
    "accessRequestsAdminPermanentAllowed",
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
    "accessRequestsAdminReferencesEnterSearch",
    "accessRequestsAdminReferencesLoadMore",
    "accessRequestsAdminReferencesLoading",
    "accessRequestsAdminRequestable",
    "accessRequestsAdminDirectEntitlementDraftOnly",
    "accessRequestsAdminTemporaryOnly",
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
    "accessRequestsAdminAutoApprove",
    "accessRequestsAdminAutoApproveHelp",
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
    "pages/AccessPackageDialog.tsx",
    "pages/DurationField.tsx",
    "pages/AccessPackageDetails.tsx",
    "pages/KeycloakReferenceSelector.tsx",
    "pages/catalogDuration.ts",
    "pages/EntitlementCatalogRoute.tsx",
    "pages/AccessRequestsAdminTabs.tsx",
    "pages/AuditEventsPage.tsx",
    "pages/AuditEventsRoute.tsx",
    "pages/AuditRequestDetailsPage.tsx",
    "pages/AuditRequestDetailsRoute.tsx",
    "pages/AuthorizedAuditPage.tsx",
    "pages/auditEventTypes.ts",
    "pages/failureCodePresentation.ts",
    "pages/FailedProvisioningPage.tsx",
    "pages/FailedProvisioningRoute.tsx",
    "pages/RevocationFailuresPage.tsx",
    "pages/RevocationFailuresRoute.tsx",
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
    it("spells out duration units and uses singular labels for one unit", () => {
        expect(messages.accessRequestsAdminDurationUnitDays).toBe("days");
        expect(messages.accessRequestsAdminDurationUnitHours).toBe("hours");
        expect(messages.accessRequestsAdminDurationUnitSeconds).toBe("seconds");
        expect(durationText(3_600, (key) => messages[key])).toBe("1 hour");
        expect(durationText(172_800, (key) => messages[key])).toBe("2 days");
        expect(durationText(1, (key) => messages[key])).toBe("1 second");
        const unitLabels: Record<string, string[]> = {
            fr: ["secondes", "heures", "jours"],
            de: ["Sekunden", "Stunden", "Tage"],
            es: ["segundos", "horas", "días"]
        };
        translatedMessages.forEach(([locale, translated]) => {
            expect([
                translated.accessRequestsAdminDurationUnitSeconds,
                translated.accessRequestsAdminDurationUnitHours,
                translated.accessRequestsAdminDurationUnitDays
            ]).toEqual(unitLabels[locale]);
        });
    });

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
