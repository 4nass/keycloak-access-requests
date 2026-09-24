import {
    Alert, Button, DataList, DataListCell, DataListItem, DataListItemCells, DataListItemRow,
    DescriptionList, DescriptionListDescription, DescriptionListGroup, DescriptionListTerm,
    EmptyState, Label, PageSection, Spinner, Text, TextContent, Title
} from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";

import { presentEntitlementsAdminError, type AdminAuditRequestDetails } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { auditEventTypes } from "./auditEventTypes";
import { failureCodeKey } from "./failureCodePresentation";

export function AuditRequestDetailsPage() {
    const { t, i18n } = useTranslation();
    const api = useEntitlementsAdminApi();
    const { realm, requestId } = useParams();
    const [details, setDetails] = useState<{ api: typeof api; requestId: string; data: AdminAuditRequestDetails }>();
    const [error, setError] = useState<{ api: typeof api; requestId: string; cause: unknown }>();
    const [retry, setRetry] = useState(0);

    useEffect(() => {
        if (!requestId) return;
        let active = true;
        setError(undefined);
        void api.auditRequest(requestId).then((next) => {
            if (active) setDetails({ api, requestId, data: next });
        }).catch((failure: unknown) => {
            if (active) setError({ api, requestId, cause: failure });
        });
        return () => { active = false; };
    }, [api, requestId, retry]);

    const currentDetails = details?.api === api && details.requestId === requestId ? details.data : undefined;
    const currentError = error?.api === api && error.requestId === requestId ? error.cause : undefined;
    const errorPresentation = currentError ? presentEntitlementsAdminError(currentError) : undefined;
    const formatDate = (date: string) => new Intl.DateTimeFormat(i18n.resolvedLanguage || "en", {
        dateStyle: "medium", timeStyle: "short"
    }).format(new Date(date));

    return <>
        <PageSection variant="light">
            <Title headingLevel="h1">{t("accessRequestsAdminEventsRequest")}: {requestId}</Title>
            <TextContent><Text component="p"><Link to={`/${encodeURIComponent(realm ?? "")}/access-requests/events`}>
                {t("accessRequestsAdminEvents")}</Link></Text></TextContent>
        </PageSection>
        <PageSection>
            {errorPresentation && <Alert isInline variant="danger"
                title={errorPresentation.requestId
                    ? `${t(errorPresentation.messageKey)} (${errorPresentation.requestId})`
                    : t(errorPresentation.messageKey)}
                actionLinks={<Button variant="link" onClick={() => setRetry((value) => value + 1)}>{t("reload")}</Button>}
            />}
            {!currentDetails && !errorPresentation && <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>}
            {currentDetails && <>
                <DescriptionList isHorizontal>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningRequester")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.requesterId}</DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningEntitlement")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.entitlementId}</DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningResource")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.resourceName}</DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsDecisionStatus")}</DescriptionListTerm>
                        <DescriptionListDescription><Label color={decisionLabels[currentDetails.decisionStatus].color}>
                            {t(decisionLabels[currentDetails.decisionStatus].key)}</Label></DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsProvisioningStatus")}</DescriptionListTerm>
                        <DescriptionListDescription><Label color={provisioningLabels[currentDetails.provisioningStatus].color}>
                            {t(provisioningLabels[currentDetails.provisioningStatus].key)}</Label></DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsOccurredAt")}</DescriptionListTerm>
                        <DescriptionListDescription>{formatDate(currentDetails.createdAt)}</DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsJustification")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.justification}</DescriptionListDescription></DescriptionListGroup>
                    {currentDetails.decision && <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsActor")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.decision.approverId}: {currentDetails.decision.comment}</DescriptionListDescription></DescriptionListGroup>}
                </DescriptionList>
                <Title headingLevel="h2" size="lg">{t("accessRequestsAdminEventsHistory")}</Title>
                <DataList aria-label={t("accessRequestsAdminEventsHistory")}>
                    {currentDetails.history.map((event, index) => <DataListItem key={`${event.type}-${event.occurredAt}-${index}`}>
                        <DataListItemRow><DataListItemCells dataListCells={[
                            <DataListCell key="type">{t(auditEventTypes[event.type])}</DataListCell>,
                            <DataListCell key="date">{formatDate(event.occurredAt)}</DataListCell>,
                            <DataListCell key="actor">{t("accessRequestsAdminEventsActor")}: {event.actorId}</DataListCell>,
                            ...(event.failureCode ? [<DataListCell key="failure-code">
                                {t("accessRequestsAdminFailureCause")}: {t(failureCodeKey(event.failureCode))}
                            </DataListCell>] : []),
                            ...(event.closureReason ? [<DataListCell key="closure-reason">
                                {t("accessRequestsAdminFailedProvisioningCloseReason")}: {event.closureReason}
                            </DataListCell>] : [])
                        ]} /></DataListItemRow>
                    </DataListItem>)}
                </DataList>
            </>}
        </PageSection>
    </>;
}

const decisionLabels = {
    PENDING: { color: "orange", key: "accessRequestsAdminDecisionPending" },
    APPROVED: { color: "green", key: "accessRequestsAdminDecisionApproved" },
    REJECTED: { color: "red", key: "accessRequestsAdminDecisionRejected" },
    CANCELED: { color: "grey", key: "accessRequestsAdminDecisionCanceled" }
} as const;

const provisioningLabels = {
    NOT_STARTED: { color: "grey", key: "accessRequestsAdminProvisioningNotStarted" },
    SUCCEEDED: { color: "green", key: "accessRequestsAdminProvisioningSucceeded" },
    FAILED: { color: "red", key: "accessRequestsAdminProvisioningFailedStatus" }
} as const;
