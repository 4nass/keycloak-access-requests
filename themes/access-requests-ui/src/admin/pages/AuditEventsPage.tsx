import {
    Alert, Button, DataList, DataListCell, DataListItem, DataListItemCells, DataListItemRow,
    EmptyState, EmptyStateBody, EmptyStateHeader, FormSelect, FormSelectOption, PageSection,
    Pagination, Spinner, Text, TextContent, TextInput, Title, Toolbar, ToolbarContent, ToolbarItem
} from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router-dom";

import {
    presentEntitlementsAdminError, type AdminAuditEventPage, type AdminAuditEventQuery,
    type AdminAuditEventType
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { AccessRequestsAdminTabs } from "./AccessRequestsAdminTabs";
import { auditEventTypes } from "./auditEventTypes";

const PAGE_SIZE_OPTIONS = [10, 20, 50].map((value) => ({ title: String(value), value }));

export function AuditEventsPage() {
    const { t, i18n } = useTranslation();
    const api = useEntitlementsAdminApi();
    const { pathname } = useLocation();
    const [filters, setFilters] = useState<AdminAuditEventQuery>({});
    const [actorInput, setActorInput] = useState("");
    const [requestInput, setRequestInput] = useState("");
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [result, setResult] = useState<AdminAuditEventPage>();
    const [error, setError] = useState<unknown>();
    const [refresh, setRefresh] = useState(0);

    useEffect(() => {
        let active = true;
        setError(undefined);
        void api.auditEvents({ ...filters, page, size }).then((next) => {
            if (active) setResult(next);
        }).catch((failure: unknown) => {
            if (active) setError(failure);
        });
        return () => { active = false; };
    }, [api, filters, page, size, refresh]);

    useEffect(() => {
        const timer = window.setTimeout(() => {
            setPage(0);
            setFilters((current) => {
                const actorId = actorInput || undefined;
                const requestId = requestInput || undefined;
                return current.actorId === actorId && current.requestId === requestId
                    ? current : { ...current, actorId, requestId };
            });
        }, 300);
        return () => window.clearTimeout(timer);
    }, [actorInput, requestInput]);

    const setFilter = <K extends keyof AdminAuditEventQuery>(key: K, value: AdminAuditEventQuery[K]) => {
        setPage(0);
        setFilters((current) => ({ ...current, [key]: value }));
    };
    const errorPresentation = error ? presentEntitlementsAdminError(error) : undefined;
    const detailBase = pathname.replace(/\/events\/?$/, "/requests");

    return <>
        <PageSection variant="light">
            <Title headingLevel="h1">{t("accessRequestsAdminEvents")}</Title>
            <TextContent><Text component="p">{t("accessRequestsAdminEventsDescription")}</Text></TextContent>
        </PageSection>
        <PageSection>
            <AccessRequestsAdminTabs active="events" />
            {errorPresentation && <Alert isInline variant="danger" className="pf-v5-u-mb-lg"
                title={errorPresentation.requestId
                    ? `${t(errorPresentation.messageKey)} (${errorPresentation.requestId})`
                    : t(errorPresentation.messageKey)}
                actionLinks={<Button variant="link" onClick={() => setRefresh((value) => value + 1)}>{t("reload")}</Button>}
            />}
            <Toolbar aria-label={t("accessRequestsAdminEvents")}>
                <ToolbarContent>
                    <ToolbarItem><label htmlFor="audit-from">{t("accessRequestsAdminEventsFrom")}</label><TextInput id="audit-from" type="date" aria-label={t("accessRequestsAdminEventsFrom")}
                        value={filters.from?.slice(0, 10) ?? ""}
                        onChange={(_event, value) => setFilter("from", value ? `${value}T00:00:00.000Z` : undefined)} /></ToolbarItem>
                    <ToolbarItem><label htmlFor="audit-to">{t("accessRequestsAdminEventsTo")}</label><TextInput id="audit-to" type="date" aria-label={t("accessRequestsAdminEventsTo")}
                        value={filters.to?.slice(0, 10) ?? ""}
                        onChange={(_event, value) => setFilter("to", value ? `${value}T23:59:59.999Z` : undefined)} /></ToolbarItem>
                    <ToolbarItem><label htmlFor="audit-type">{t("accessRequestsAdminEventsType")}</label><FormSelect id="audit-type" aria-label={t("accessRequestsAdminEventsType")}
                        value={filters.type ?? ""} onChange={(_event, value) => setFilter("type", value ? value as AdminAuditEventType : undefined)}>
                        <FormSelectOption value="" label={t("accessRequestsAdminEventsAllTypes")} />
                        {Object.entries(auditEventTypes).map(([type, key]) =>
                            <FormSelectOption key={type} value={type} label={t(key)} />)}
                    </FormSelect></ToolbarItem>
                    <ToolbarItem><label htmlFor="audit-actor-id">{t("accessRequestsAdminEventsActor")}</label><TextInput id="audit-actor-id" aria-label={t("accessRequestsAdminEventsActor")}
                        placeholder={t("accessRequestsAdminEventsActor")}
                        value={actorInput} onChange={(_event, value) => setActorInput(value)} /></ToolbarItem>
                    <ToolbarItem><label htmlFor="audit-request-id">{t("accessRequestsAdminEventsRequest")}</label><TextInput id="audit-request-id" aria-label={t("accessRequestsAdminEventsRequest")}
                        placeholder={t("accessRequestsAdminEventsRequest")}
                        value={requestInput} onChange={(_event, value) => setRequestInput(value)} /></ToolbarItem>
                    {result && result.total > 0 && <ToolbarItem align={{ default: "alignRight" }} variant="pagination">
                        <AuditPagination page={page} size={size} total={result.total} onPage={setPage} onSize={(next) => { setPage(0); setSize(next); }} />
                    </ToolbarItem>}
                </ToolbarContent>
            </Toolbar>
            {!result && error ? null : !result ? <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
                : result?.items.length ? <DataList aria-label={t("accessRequestsAdminEvents")}>
                    {result.items.map((item) => <DataListItem key={item.id}>
                        <DataListItemRow><DataListItemCells dataListCells={[
                            <DataListCell key="event"><Text component="p">{t(auditEventTypes[item.type])}</Text></DataListCell>,
                            <DataListCell key="date"><Text component="p">{t("accessRequestsAdminEventsOccurredAt")}: {new Intl.DateTimeFormat(i18n.resolvedLanguage || "en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(item.occurredAt))}</Text></DataListCell>,
                            <DataListCell key="actor"><Text component="p">{t("accessRequestsAdminEventsActor")}: {item.actorId}</Text></DataListCell>,
                            <DataListCell key="request"><Link to={`${detailBase}/${encodeURIComponent(item.requestId)}`}>
                                {t("accessRequestsAdminEventsViewRequest")}: {item.requestId}</Link></DataListCell>
                        ]} /></DataListItemRow>
                    </DataListItem>)}
                </DataList>
                : <EmptyState><EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminEventsEmpty")} />
                    <EmptyStateBody>{t("accessRequestsAdminEventsDescription")}</EmptyStateBody></EmptyState>}
            {result && result.total > 0 && <AuditPagination page={page} size={size} total={result.total}
                onPage={setPage} onSize={(next) => { setPage(0); setSize(next); }} variant="bottom" />}
        </PageSection>
    </>;
}

function AuditPagination({ page, size, total, onPage, onSize, variant }: {
    page: number; size: number; total: number; onPage: (page: number) => void;
    onSize: (size: number) => void; variant?: "bottom";
}) {
    return <Pagination itemCount={total} page={page + 1} perPage={size} perPageOptions={PAGE_SIZE_OPTIONS}
        variant={variant} onSetPage={(_event, next) => onPage(next - 1)}
        onPerPageSelect={(_event, next) => onSize(next)} />;
}
