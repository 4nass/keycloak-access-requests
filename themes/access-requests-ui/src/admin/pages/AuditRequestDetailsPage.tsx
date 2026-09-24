import {
    Alert, Button, DataList, DataListCell, DataListItem, DataListItemCells, DataListItemRow,
    DescriptionList, DescriptionListDescription, DescriptionListGroup, DescriptionListTerm,
    EmptyState, PageSection, Spinner, Text, TextContent, Title
} from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";

import { presentEntitlementsAdminError, type AdminAuditRequestDetails } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { auditEventTypes } from "./auditEventTypes";

export function AuditRequestDetailsPage() {
    const { t, i18n } = useTranslation();
    const api = useEntitlementsAdminApi();
    const { realm, requestId } = useParams();
    const [details, setDetails] = useState<AdminAuditRequestDetails>();
    const [error, setError] = useState<unknown>();
    const [retry, setRetry] = useState(0);

    useEffect(() => {
        if (!requestId) return;
        let active = true;
        setError(undefined);
        void api.auditRequest(requestId).then((next) => {
            if (active) setDetails(next);
        }).catch((failure: unknown) => {
            if (active) setError(failure);
        });
        return () => { active = false; };
    }, [api, requestId, retry]);

    const errorPresentation = error ? presentEntitlementsAdminError(error) : undefined;
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
            {!details && !error && <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>}
            {details && <>
                <DescriptionList isHorizontal>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningEntitlement")}</DescriptionListTerm>
                        <DescriptionListDescription>{details.entitlementId}</DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningResource")}</DescriptionListTerm>
                        <DescriptionListDescription>{details.resourceName}</DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsOccurredAt")}</DescriptionListTerm>
                        <DescriptionListDescription>{formatDate(details.createdAt)}</DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsJustification")}</DescriptionListTerm>
                        <DescriptionListDescription>{details.justification}</DescriptionListDescription></DescriptionListGroup>
                    {details.decision && <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsActor")}</DescriptionListTerm>
                        <DescriptionListDescription>{details.decision.approverId}: {details.decision.comment}</DescriptionListDescription></DescriptionListGroup>}
                </DescriptionList>
                <Title headingLevel="h2" size="lg">{t("accessRequestsAdminEventsHistory")}</Title>
                <DataList aria-label={t("accessRequestsAdminEventsHistory")}>
                    {details.history.map((event, index) => <DataListItem key={`${event.type}-${event.occurredAt}-${index}`}>
                        <DataListItemRow><DataListItemCells dataListCells={[
                            <DataListCell key="type">{t(auditEventTypes[event.type])}</DataListCell>,
                            <DataListCell key="date">{formatDate(event.occurredAt)}</DataListCell>
                        ]} /></DataListItemRow>
                    </DataListItem>)}
                </DataList>
            </>}
        </PageSection>
    </>;
}
