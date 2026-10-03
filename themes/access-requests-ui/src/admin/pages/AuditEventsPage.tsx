import {
    Alert, Button, Chip, ChipGroup, EmptyState, EmptyStateBody, EmptyStateHeader, Form,
    FormGroup, FormSelect, FormSelectOption, Grid, GridItem, PageSection,
    Pagination, Spinner, Text, TextContent, TextInput, Title, Toolbar, ToolbarContent, ToolbarItem
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { CaretDownIcon, CaretUpIcon, FilterIcon } from "@patternfly/react-icons";
import { type FormEvent, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, Outlet, useParams } from "react-router-dom";

import {
    presentEntitlementsAdminError, type AdminAuditEventPage, type AdminAuditEventQuery,
    type AdminAuditEventType, type AuditUser
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { AccessRequestsAdminTabs } from "./AccessRequestsAdminTabs";
import { AuditUserSelector } from "./AuditUserSelector";
import { adminActorLabel } from "./adminActorLabel";
import { auditEventTypes } from "./auditEventTypes";

const PAGE_SIZE_OPTIONS = [10, 20, 50].map((value) => ({ title: String(value), value }));

type AuditFilters = {
    fromDay: string; toDay: string; type: AdminAuditEventType | "";
    requester: AuditUser | null; actor: AuditUser | null; requestId: string;
};
const emptyFilters = (): AuditFilters => ({
    fromDay: "", toDay: "", type: "", requester: null, actor: null, requestId: ""
});

export function localAuditDayBoundary(day: string, endOfDay: boolean): string {
    const [year, month, date] = day.split("-").map(Number);
    return new Date(year, month - 1, date, endOfDay ? 23 : 0, endOfDay ? 59 : 0,
        endOfDay ? 59 : 0, endOfDay ? 999 : 0).toISOString();
}

function toQuery(filters: AuditFilters): AdminAuditEventQuery {
    return {
        from: filters.fromDay ? localAuditDayBoundary(filters.fromDay, false) : undefined,
        to: filters.toDay ? localAuditDayBoundary(filters.toDay, true) : undefined,
        type: filters.type || undefined,
        requesterId: filters.requester?.id,
        actorId: filters.actor?.id,
        requestId: filters.requestId || undefined
    };
}

export function AuditEventsPage() {
    const { t, i18n } = useTranslation();
    const api = useEntitlementsAdminApi();
    const { realm } = useParams();
    const [draft, setDraft] = useState<AuditFilters>(emptyFilters);
    const [applied, setApplied] = useState<AuditFilters>(emptyFilters);
    const [searchOpen, setSearchOpen] = useState(false);
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [result, setResult] = useState<{ api: typeof api; key: string; data: AdminAuditEventPage }>();
    const [error, setError] = useState<{ api: typeof api; key: string; cause: unknown }>();
    const [refresh, setRefresh] = useState(0);
    const queryKey = JSON.stringify({ applied, page, size });

    useEffect(() => {
        let active = true;
        setError(undefined);
        void api.auditEvents({ ...toQuery(applied), page, size }).then((next) => {
            if (active) setResult({ api, key: queryKey, data: next });
        }).catch((failure: unknown) => {
            if (active) setError({ api, key: queryKey, cause: failure });
        });
        return () => { active = false; };
    }, [api, queryKey, refresh]);

    const updateDraft = <K extends keyof AuditFilters>(key: K, value: AuditFilters[K]) => {
        setDraft((current) => ({ ...current, [key]: value }));
    };
    const applySearch = (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setPage(0);
        setApplied({ ...draft, requestId: draft.requestId.trim() });
        setSearchOpen(false);
    };
    const clearFilters = () => {
        setDraft(emptyFilters());
        setApplied(emptyFilters());
        setPage(0);
    };
    const removeFilter = (key: keyof AuditFilters) => {
        const empty = key === "requester" || key === "actor" ? null : "";
        setDraft((current) => ({ ...current, [key]: empty }));
        setApplied((current) => ({ ...current, [key]: empty }));
        setPage(0);
    };
    const activeFilters = (Object.keys(applied) as (keyof AuditFilters)[]).filter((key) => applied[key]);
    const displayedResult = result?.api === api && result.key === queryKey ? result.data : undefined;
    const currentError = error?.api === api && error.key === queryKey ? error.cause : undefined;
    const errorPresentation = currentError ? presentEntitlementsAdminError(currentError) : undefined;
    const detailBase = `/${encodeURIComponent(realm ?? "")}/access-requests/requests`;
    const filterLabels: Record<keyof AuditFilters, string> = {
        fromDay: t("accessRequestsAdminEventsFrom"), toDay: t("accessRequestsAdminEventsTo"),
        type: t("accessRequestsAdminEventsType"),
        requester: t("accessRequestsAdminEventsRequester"),
        actor: t("accessRequestsAdminEventsActor"),
        requestId: t("accessRequestsAdminEventsRequest")
    };

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
                    <ToolbarItem><Button variant="secondary" icon={<FilterIcon />}
                        aria-expanded={searchOpen} aria-controls={searchOpen ? "audit-event-search" : undefined}
                        onClick={() => setSearchOpen((open) => !open)}>
                        {t(searchOpen ? "accessRequestsAdminEventsHideFilters" : "accessRequestsAdminEventsFilters")}
                        {searchOpen ? <CaretUpIcon className="pf-v5-u-ml-sm" />
                            : <CaretDownIcon className="pf-v5-u-ml-sm" />}
                    </Button></ToolbarItem>
                    {displayedResult && displayedResult.total > 0 && <ToolbarItem align={{ default: "alignRight" }} variant="pagination">
                        <AuditPagination page={page} size={size} total={displayedResult.total} onPage={setPage} onSize={(next) => { setPage(0); setSize(next); }} />
                    </ToolbarItem>}
                </ToolbarContent>
            </Toolbar>
            {searchOpen && <Form id="audit-event-search" onSubmit={applySearch} className="pf-v5-u-p-lg pf-v5-u-mb-md pf-v5-u-background-color-100">
                <Grid hasGutter>
                    <GridItem sm={6} lg={4}><FormGroup label={filterLabels.fromDay} fieldId="audit-from">
                        <TextInput id="audit-from" type="date" value={draft.fromDay}
                            onChange={(_event, value) => updateDraft("fromDay", value)} />
                    </FormGroup></GridItem>
                    <GridItem sm={6} lg={4}><FormGroup label={filterLabels.toDay} fieldId="audit-to">
                        <TextInput id="audit-to" type="date" value={draft.toDay}
                            onChange={(_event, value) => updateDraft("toDay", value)} />
                    </FormGroup></GridItem>
                    <GridItem sm={6} lg={4}><FormGroup label={filterLabels.type} fieldId="audit-type">
                        <FormSelect id="audit-type" value={draft.type}
                            onChange={(_event, value) => updateDraft("type", value as AdminAuditEventType | "")}>
                            <FormSelectOption value="" label={t("accessRequestsAdminEventsAllTypes")} />
                            {Object.entries(auditEventTypes).map(([type, key]) =>
                                <FormSelectOption key={type} value={type} label={t(key)} />)}
                        </FormSelect>
                    </FormGroup></GridItem>
                    <GridItem sm={6} lg={4}><FormGroup label={filterLabels.requester} fieldId="audit-requester">
                        <AuditUserSelector api={api} fieldId="audit-requester" value={draft.requester}
                            onSelect={(user) => updateDraft("requester", user)}
                            searchLabel="accessRequestsAdminEventsSearchRequester" />
                    </FormGroup></GridItem>
                    <GridItem sm={6} lg={4}><FormGroup label={filterLabels.actor} fieldId="audit-actor">
                        <AuditUserSelector api={api} fieldId="audit-actor" value={draft.actor} includeSystem
                            onSelect={(user) => updateDraft("actor", user)}
                            searchLabel="accessRequestsAdminEventsSearchActor" />
                    </FormGroup></GridItem>
                    <GridItem sm={6} lg={4}><FormGroup label={filterLabels.requestId} fieldId="audit-request-id">
                        <TextInput id="audit-request-id" value={draft.requestId}
                            onChange={(_event, value) => updateDraft("requestId", value)} />
                    </FormGroup></GridItem>
                </Grid>
                <div><Button type="submit">{t("accessRequestsAdminEventsApply")}</Button>{" "}
                    <Button variant="link" type="button" onClick={clearFilters}>{t("accessRequestsAdminEventsClear")}</Button></div>
            </Form>}
            {activeFilters.length > 0 && <div className="pf-v5-u-mb-md">
                <ChipGroup categoryName={t("accessRequestsAdminEventsActiveFilters")}>
                    {activeFilters.map((key) => <Chip key={key} onClick={() => removeFilter(key)}>
                        {filterLabels[key]}: {key === "type" ? t(auditEventTypes[applied.type as AdminAuditEventType])
                            : key === "requester" || key === "actor" ? applied[key]?.name : applied[key]}
                    </Chip>)}
                </ChipGroup>
                <Button variant="link" onClick={clearFilters}>{t("accessRequestsAdminEventsClear")}</Button>
            </div>}
            {!displayedResult && errorPresentation ? null : !displayedResult ? <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
                : displayedResult.items.length ? <Table role="table" variant="compact" aria-label={t("accessRequestsAdminEvents")}>
                    <Thead><Tr>
                        <Th>{t("accessRequestsAdminEventsOccurredAt")}</Th>
                        <Th>{t("accessRequestsAdminEventsType")}</Th>
                        <Th>{t("accessRequestsAdminEventsActor")}</Th>
                        <Th>{t("accessRequestsAdminEventsRequestTitle")}</Th>
                    </Tr></Thead>
                    <Tbody>{displayedResult.items.map((item) => <Tr key={item.id}>
                        <Td>{new Intl.DateTimeFormat(i18n.resolvedLanguage || "en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(item.occurredAt))}</Td>
                        <Td>{t(auditEventTypes[item.type])}</Td>
                        <Td>{adminActorLabel(item.actorId, item.actorName, t)}</Td>
                        <Td><Link to={`${detailBase}/${encodeURIComponent(item.requestId)}`}>
                            {item.requestName ?? t("accessRequestsAdminNotAvailable")}</Link></Td>
                    </Tr>)}</Tbody>
                </Table>
                : <EmptyState><EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminEventsEmpty")} />
                    <EmptyStateBody>{t("accessRequestsAdminEventsDescription")}</EmptyStateBody></EmptyState>}
            {displayedResult && displayedResult.total > 0 && <AuditPagination page={page} size={size} total={displayedResult.total}
                onPage={setPage} onSize={(next) => { setPage(0); setSize(next); }} variant="bottom" />}
        </PageSection>
        <Outlet />
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
