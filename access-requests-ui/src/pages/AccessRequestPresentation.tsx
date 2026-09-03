import { Label, type LabelProps } from "@patternfly/react-core";
import type { TFunction } from "i18next";

type LabelColor = NonNullable<LabelProps["color"]>;

type Presentation = {
    color: LabelColor;
    key: string;
};

const decisionStatuses: Record<string, Presentation> = {
    APPROVED: { color: "green", key: "accessRequestsApproved" },
    CANCELED: { color: "grey", key: "accessRequestsCanceled" },
    PENDING: { color: "orange", key: "accessRequestsPending" },
    REJECTED: { color: "red", key: "accessRequestsRejected" }
};

const historyEvents: Record<string, Presentation> = {
    PROVISIONING_FAILED: { color: "red", key: "accessRequestsHistoryProvisioningFailed" },
    PROVISIONING_STARTED: { color: "blue", key: "accessRequestsHistoryProvisioningStarted" },
    PROVISIONING_SUCCEEDED: { color: "green", key: "accessRequestsHistoryProvisioningSucceeded" },
    REQUEST_APPROVED: { color: "green", key: "accessRequestsHistoryRequestApproved" },
    REQUEST_CANCELED: { color: "grey", key: "accessRequestsHistoryRequestCanceled" },
    REQUEST_CREATED: { color: "blue", key: "accessRequestsHistoryRequestCreated" },
    REQUEST_REJECTED: { color: "red", key: "accessRequestsHistoryRequestRejected" }
};

const provisioningStatuses: Record<string, Presentation> = {
    FAILED: { color: "red", key: "accessRequestsProvisioningFailed" },
    NOT_STARTED: { color: "grey", key: "accessRequestsProvisioningNotStarted" },
    SUCCEEDED: { color: "green", key: "accessRequestsProvisioningSucceeded" }
};

const resourceTypes: Record<string, string> = {
    CLIENT_ROLE: "accessRequestsResourceTypeClientRole",
    GROUP: "accessRequestsResourceTypeGroup",
    REALM_ROLE: "accessRequestsResourceTypeRealmRole"
};

const riskLevels: Record<string, Presentation> = {
    CRITICAL: { color: "red", key: "accessRequestsRiskCritical" },
    HIGH: { color: "orange", key: "accessRequestsRiskHigh" },
    LOW: { color: "green", key: "accessRequestsRiskLow" },
    MEDIUM: { color: "blue", key: "accessRequestsRiskMedium" }
};

function fallback(value: string) {
    return value.toLowerCase().replaceAll("_", " ").replace(/^./, (character) => character.toUpperCase());
}

function translate(t: TFunction, key: string, fallbackValue: string) {
    return t(key, { defaultValue: fallbackValue });
}

function PresentationLabel({ presentation, t, value }: { presentation?: Presentation; t: TFunction; value: string }) {
    return (
        <Label color={presentation?.color ?? "grey"} isCompact>
            {presentation ? translate(t, presentation.key, fallback(value)) : fallback(value)}
        </Label>
    );
}

export function DecisionStatusLabel({ status, t }: { status: string; t: TFunction }) {
    return <PresentationLabel presentation={decisionStatuses[status]} t={t} value={status} />;
}

export function HistoryEventLabel({ event, t }: { event: string; t: TFunction }) {
    return <PresentationLabel presentation={historyEvents[event]} t={t} value={event} />;
}

export function ProvisioningStatusLabel({ status, t }: { status: string; t: TFunction }) {
    return <PresentationLabel presentation={provisioningStatuses[status]} t={t} value={status} />;
}

export function RiskLevelLabel({ riskLevel, t }: { riskLevel: string; t: TFunction }) {
    return <PresentationLabel presentation={riskLevels[riskLevel]} t={t} value={riskLevel} />;
}

export function resourceTypeLabel(resourceType: string, t: TFunction) {
    return translate(t, resourceTypes[resourceType] ?? resourceType, fallback(resourceType));
}

export function formatDateTime(value: string, locale: string) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat(locale, {
        dateStyle: "medium",
        timeStyle: "short"
    }).format(date);
}
