import {
    Alert, Button, ButtonVariant, DataList, DataListAction, DataListCell, DataListItem,
    DataListItemCells, DataListItemRow, DescriptionList, DescriptionListDescription,
    DescriptionListGroup, DescriptionListTerm, EmptyState, EmptyStateBody, EmptyStateHeader,
    Form, FormGroup, FormSelect, FormSelectOption, Label, Modal, ModalVariant,
    Spinner, TextArea, TextInput, Title, Toolbar, ToolbarContent, ToolbarItem
} from "@patternfly/react-core";
import { type FormEvent, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type AdminIncident,
    type AdminIncidentPage,
    type IncidentState,
    type IncidentType,
    type RevocationFailureCode
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { failureCodeKey } from "./failureCodePresentation";

const PAGE_SIZE_OPTIONS = [10, 20, 50];
type Action = "NOTIFICATION_RETRY" | "PROVISIONING_RETRY" | "PROVISIONING_CLOSE"
    | "REVOCATION_RETRY" | "REVOCATION_RESOLVE";

export function IncidentsPage() {
    const { i18n, t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [type, setType] = useState<IncidentType>("ALL");
    const [state, setState] = useState<IncidentState>("OPEN");
    const [cursors, setCursors] = useState<(string | null)[]>([null]);
    const [size, setSize] = useState(20);
    const [dateFiltersOpen, setDateFiltersOpen] = useState(false);
    const [draftFrom, setDraftFrom] = useState("");
    const [draftTo, setDraftTo] = useState("");
    const [fromDay, setFromDay] = useState("");
    const [toDay, setToDay] = useState("");
    const [dateError, setDateError] = useState(false);
    const [revision, setRevision] = useState(0);
    const [result, setResult] = useState<{ key: string; page: AdminIncidentPage }>();
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<unknown>();
    const [action, setAction] = useState<{ kind: Action; incident: AdminIncident }>();
    const [reason, setReason] = useState("");
    const [actionError, setActionError] = useState<unknown>();
    const [actionNotice, setActionNotice] = useState<string>();
    const [working, setWorking] = useState(false);
    const staleDiagnostics = useRef(new Set<string>());
    const locallyHandled = useRef(new Set<string>());
    const actionInFlight = useRef(false);
    const latestLoad = useRef(0);
    const page = cursors.length - 1;
    const cursor = cursors[page] ?? undefined;
    const from = fromDay ? localDayBoundary(fromDay, false) : undefined;
    const to = toDay ? localDayBoundary(toDay, true) : undefined;
    const queryKey = JSON.stringify({ type, state, cursor, size, from, to });
    const visible = result?.key === queryKey ? result.page : undefined;
    const hasNavigation = Boolean(visible && (page > 0 || visible.nextCursor));

    useEffect(() => {
        const loadId = ++latestLoad.current;
        setLoading(true);
        setLoadError(undefined);
        void api.incidents({ type, state, from, to, cursor, size }).then((next) => {
            if (loadId !== latestLoad.current) return;
            staleDiagnostics.current.clear();
            const returnedKeys = new Set(next.items.map((incident) => `${incident.type}:${incident.id}`));
            for (const key of locallyHandled.current) {
                if (!returnedKeys.has(key)) locallyHandled.current.delete(key);
            }
            const items = next.items.filter((incident) => incident.state !== "OPEN"
                || !locallyHandled.current.has(`${incident.type}:${incident.id}`));
            const adjusted = { ...next, items };
            if (page > 0 && !adjusted.items.length && !adjusted.nextCursor) {
                setCursors((current) => current.slice(0, -1));
                return;
            }
            setResult({ key: queryKey, page: adjusted });
        }).catch((error: unknown) => {
            if (loadId === latestLoad.current) setLoadError(error);
        }).finally(() => {
            if (loadId === latestLoad.current) setLoading(false);
        });
        return () => { latestLoad.current += 1; };
    }, [api, type, state, from, to, cursor, size, revision, queryKey, page]);

    const openAction = (kind: Action, incident: AdminIncident) => {
        setAction({ kind, incident });
        setReason("");
        setActionError(undefined);
        setActionNotice(undefined);
    };
    const closeAction = () => {
        if (!actionInFlight.current) {
            setAction(undefined);
            setActionError(undefined);
            setReason("");
        }
    };
    const removeHandled = (incident: AdminIncident, suppressOnRefresh: boolean) => {
        if (suppressOnRefresh) locallyHandled.current.add(`${incident.type}:${incident.id}`);
        setResult((current) => current && current.key === queryKey ? {
            ...current,
            page: {
                ...current.page,
                items: current.page.items.filter((item) => !(item.type === incident.type && item.id === incident.id))
            }
        } : current);
    };
    const submitAction = async () => {
        if (!action || actionInFlight.current) return;
        const requiresReason = action.kind === "PROVISIONING_CLOSE" || action.kind === "REVOCATION_RESOLVE";
        if (requiresReason && (reason.trim().length < 10 || reason.trim().length > 1000)) return;
        actionInFlight.current = true;
        setWorking(true);
        setActionError(undefined);
        try {
            let resolved = true;
            let notice: string;
            switch (action.kind) {
                case "NOTIFICATION_RETRY":
                    await api.retryNotificationDelivery(action.incident.id);
                    notice = "accessRequestsAdminNotificationDeliveryRetryQueued";
                    break;
                case "PROVISIONING_RETRY": {
                    const outcome = await api.retryFailedProvisioning(action.incident.id);
                    resolved = outcome.provisioningStatus === "SUCCEEDED";
                    notice = resolved ? "accessRequestsAdminFailedProvisioningRetrySuccess"
                        : "accessRequestsAdminFailedProvisioningRetryStillFailed";
                    break;
                }
                case "PROVISIONING_CLOSE":
                    await api.closeFailedProvisioning(action.incident.id, reason.trim());
                    notice = "accessRequestsAdminFailedProvisioningCloseSuccess";
                    break;
                case "REVOCATION_RETRY": {
                    const outcome = await api.retryGrantRevocation(action.incident.id);
                    resolved = outcome.status === "REVOKED";
                    notice = resolved ? "accessRequestsAdminRevocationRetrySuccess"
                        : "accessRequestsAdminRevocationRetryFailed";
                    break;
                }
                case "REVOCATION_RESOLVE":
                    await api.resolveGrantRevocation(action.incident.id, reason.trim());
                    notice = "accessRequestsAdminRevocationConfirmedRemoved";
                    break;
            }
            if (resolved) removeHandled(action.incident, state === "OPEN"
                || action.kind === "NOTIFICATION_RETRY" || action.kind === "PROVISIONING_RETRY");
            if (!resolved) staleDiagnostics.current.add(`${action.incident.type}:${action.incident.id}`);
            setActionNotice(notice);
            setAction(undefined);
            setReason("");
            setRevision((value) => value + 1);
        } catch (error) {
            setActionError(error);
        } finally {
            actionInFlight.current = false;
            setWorking(false);
        }
    };

    const selectType = (next: IncidentType) => {
        setType(next);
        if (next === "NOTIFICATION" && state === "CLOSED") setState("OPEN");
        setCursors([null]);
        setActionNotice(undefined);
    };
    const selectState = (next: IncidentState) => {
        setState(next);
        setCursors([null]);
        setActionNotice(undefined);
    };
    const applyDates = (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (draftFrom && draftTo && draftFrom > draftTo) {
            setDateError(true);
            return;
        }
        setDateError(false);
        setFromDay(draftFrom);
        setToDay(draftTo);
        setCursors([null]);
        setDateFiltersOpen(false);
    };
    const clearDates = () => {
        setDraftFrom("");
        setDraftTo("");
        setFromDay("");
        setToDay("");
        setDateError(false);
        setCursors([null]);
    };
    const errorText = (error: unknown) => {
        const presentation = presentEntitlementsAdminError(error);
        return `${t(presentation.messageKey)}${presentation.requestId ? ` (${presentation.requestId})` : ""}`;
    };
    const actionKey = action ? {
        NOTIFICATION_RETRY: "accessRequestsAdminNotificationDeliveryRetry",
        PROVISIONING_RETRY: "accessRequestsAdminFailedProvisioningRetry",
        PROVISIONING_CLOSE: "accessRequestsAdminFailedProvisioningClose",
        REVOCATION_RETRY: "accessRequestsAdminRevocationRetry",
        REVOCATION_RESOLVE: "accessRequestsAdminRevocationConfirmRemoved"
    }[action.kind] : "";
    const actionDescriptionKey = action ? {
        NOTIFICATION_RETRY: "accessRequestsAdminNotificationDeliveryRetryDescription",
        PROVISIONING_RETRY: "accessRequestsAdminFailedProvisioningRetryDescription",
        PROVISIONING_CLOSE: "accessRequestsAdminFailedProvisioningCloseDescription",
        REVOCATION_RETRY: "accessRequestsAdminRevocationRetryWarning",
        REVOCATION_RESOLVE: "accessRequestsAdminRevocationConfirmRemovedWarning"
    }[action.kind] : "";
    const needsReason = action?.kind === "PROVISIONING_CLOSE" || action?.kind === "REVOCATION_RESOLVE";

    return <>
        {actionNotice && <Alert isInline className="pf-v5-u-mb-lg"
            variant={actionNotice === "accessRequestsAdminRevocationRetryFailed"
                || actionNotice === "accessRequestsAdminFailedProvisioningRetryStillFailed" ? "warning" : "success"}
            title={t(actionNotice)} />}
        {loadError && <Alert isInline className="pf-v5-u-mb-lg" variant="danger" title={errorText(loadError)}
            actionClose={<Button variant={ButtonVariant.plain} aria-label={t("close")} onClick={() => setLoadError(undefined)} />}
            actionLinks={<Button variant="link" onClick={() => setRevision((value) => value + 1)}>{t("reload")}</Button>} />}
        <Toolbar aria-label={t("accessRequestsAdminIncidents")}>
            <ToolbarContent>
                <ToolbarItem><FormGroup label={t("accessRequestsAdminIncidentsType")} fieldId="incident-type">
                    <FormSelect id="incident-type" value={type}
                        onChange={(_event, value) => selectType(value as IncidentType)}>
                        <FormSelectOption value="ALL" label={t("accessRequestsAdminIncidentsAllTypes")} />
                        <FormSelectOption value="NOTIFICATION" label={t("accessRequestsAdminIncidentsNotification")} />
                        <FormSelectOption value="PROVISIONING" label={t("accessRequestsAdminIncidentsProvisioning")} />
                        <FormSelectOption value="REVOCATION" label={t("accessRequestsAdminIncidentsRevocation")} />
                    </FormSelect>
                </FormGroup></ToolbarItem>
                <ToolbarItem><FormGroup label={t("accessRequestsAdminIncidentsState")} fieldId="incident-state">
                    <FormSelect id="incident-state" value={state}
                        onChange={(_event, value) => selectState(value as IncidentState)}>
                        <FormSelectOption value="OPEN" label={t("accessRequestsAdminIncidentsOpen")} />
                        <FormSelectOption value="CLOSED" isDisabled={type === "NOTIFICATION"}
                            label={t("accessRequestsAdminIncidentsClosed")} />
                        <FormSelectOption value="ALL" label={t("accessRequestsAdminIncidentsAllStates")} />
                    </FormSelect>
                </FormGroup></ToolbarItem>
                <ToolbarItem><Button variant="secondary" aria-expanded={dateFiltersOpen}
                    aria-controls="incident-date-filters" onClick={() => setDateFiltersOpen((open) => !open)}>
                    {t("accessRequestsAdminIncidentsDateFilters")}
                </Button></ToolbarItem>
                {(fromDay || toDay) && <ToolbarItem><Label color="blue">
                    {[fromDay && `${t("accessRequestsAdminEventsFrom")}: ${fromDay}`,
                        toDay && `${t("accessRequestsAdminEventsTo")}: ${toDay}`].filter(Boolean).join(" · ")}
                </Label></ToolbarItem>}
                <ToolbarItem><FormGroup label={t("accessRequestsAdminIncidentsPerPage")} fieldId="incident-size">
                    <FormSelect id="incident-size" value={size} onChange={(_event, value) => {
                        setSize(Number(value)); setCursors([null]);
                    }}>
                        {PAGE_SIZE_OPTIONS.map((option) => <FormSelectOption key={option}
                            value={option} label={String(option)} />)}
                    </FormSelect>
                </FormGroup></ToolbarItem>
                {hasNavigation && visible && <ToolbarItem align={{ default: "alignRight" }}>
                    <IncidentNavigation page={page} nextCursor={visible.nextCursor}
                        onPrevious={() => setCursors((current) => current.slice(0, -1))}
                        onNext={() => setCursors((current) => [...current, visible.nextCursor])} />
                </ToolbarItem>}
            </ToolbarContent>
        </Toolbar>
        {dateFiltersOpen && <Form id="incident-date-filters" onSubmit={applyDates}
            className="pf-v5-u-p-lg pf-v5-u-mb-md pf-v5-u-background-color-100">
            <FormGroup label={t("accessRequestsAdminEventsFrom")} fieldId="incident-from">
                <TextInput id="incident-from" type="date" value={draftFrom}
                    onChange={(_event, value) => { setDraftFrom(value); setDateError(false); }} />
            </FormGroup>
            <FormGroup label={t("accessRequestsAdminEventsTo")} fieldId="incident-to">
                <TextInput id="incident-to" type="date" value={draftTo}
                    onChange={(_event, value) => { setDraftTo(value); setDateError(false); }} />
            </FormGroup>
            {dateError && <Alert isInline variant="danger" title={t("accessRequestsAdminIncidentsInvalidDates")} />}
            <div><Button type="submit">{t("accessRequestsAdminIncidentsApplyDates")}</Button>{" "}
                <Button variant="link" type="button" onClick={clearDates}>
                    {t("accessRequestsAdminEventsClear")}</Button></div>
        </Form>}
        {!visible && loading ? <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
            : visible?.items.length ? <DataList aria-label={t("accessRequestsAdminIncidents")}>
                {visible.items.map((incident) => <IncidentRow key={`${incident.type}:${incident.id}`}
                    incident={incident} locale={i18n.resolvedLanguage || "en"} onAction={openAction}
                    stale={staleDiagnostics.current.has(`${incident.type}:${incident.id}`)} />)}
            </DataList> : visible ? <EmptyState>
                <EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminIncidentsEmpty")} />
                <EmptyStateBody>{t("accessRequestsAdminIncidentsDescription")}</EmptyStateBody>
            </EmptyState> : null}
        {hasNavigation && visible && <IncidentNavigation page={page} nextCursor={visible.nextCursor}
            onPrevious={() => setCursors((current) => current.slice(0, -1))}
            onNext={() => setCursors((current) => [...current, visible.nextCursor])} />}
        {action && <Modal isOpen variant={ModalVariant.small} title={t(actionKey)} onClose={closeAction}
            actions={[
                <Button key="confirm" isDisabled={working || (needsReason && reason.trim().length < 10)}
                    isLoading={working} onClick={() => void submitAction()}>{t(actionKey)}</Button>,
                <Button key="cancel" variant="link" isDisabled={working} onClick={closeAction}>
                    {t("accessRequestsAdminCancel")}</Button>
            ]}>
            {Boolean(actionError) && <Alert isInline variant="danger" className="pf-v5-u-mb-lg"
                title={errorText(actionError)} />}
            <Alert isInline variant="warning" className="pf-v5-u-mb-lg" title={t(actionDescriptionKey)} />
            <p className="pf-v5-u-mb-md">{action.incident.notification?.entitlementName
                ?? action.incident.provisioning?.entitlementName
                ?? action.incident.revocation?.entitlementName}</p>
            {needsReason && <Form onSubmit={(event) => { event.preventDefault(); void submitAction(); }}>
                <FormGroup isRequired fieldId="incident-reason" label={t(action.kind === "PROVISIONING_CLOSE"
                    ? "accessRequestsAdminFailedProvisioningCloseReason"
                    : "accessRequestsAdminRevocationResolutionReason")}>
                    <TextArea id="incident-reason" value={reason} maxLength={1000}
                        onChange={(_event, value) => setReason(value)} />
                </FormGroup>
            </Form>}
        </Modal>}
    </>;
}

function IncidentRow({ incident, locale, onAction, stale }: {
    incident: AdminIncident;
    locale: string;
    onAction: (kind: Action, incident: AdminIncident) => void;
    stale: boolean;
}) {
    const { t } = useTranslation();
    const notification = incident.notification;
    const provisioning = incident.provisioning;
    const revocation = incident.revocation;
    const title = notification?.entitlementName ?? provisioning?.entitlementName
        ?? revocation?.entitlementName ?? provisioning?.resourceName
        ?? revocation?.resourceName ?? t("accessRequestsAdminNotAvailable");
    const person = notification?.recipientName ?? provisioning?.requesterName
        ?? revocation?.requesterName ?? t("accessRequestsAdminUserUnavailable");
    const typeKey = {
        NOTIFICATION: "accessRequestsAdminIncidentsNotification",
        PROVISIONING: "accessRequestsAdminIncidentsProvisioning",
        REVOCATION: "accessRequestsAdminIncidentsRevocation"
    }[incident.type];
    const cause = stale ? t("accessRequestsAdminIncidentsDiagnosticPending")
        : provisioning ? t(failureCodeKey(provisioning.failureCode))
            : revocation ? t(revocationCodeKey(revocation.failureCode)) : null;
    const titleId = `incident-${incident.type.toLowerCase()}-${incident.id}`;
    return <DataListItem aria-labelledby={titleId}>
        <DataListItemRow>
            <DataListItemCells dataListCells={[
                <DataListCell key="subject" width={3}>
                    <Title headingLevel="h3" size="md" id={titleId}>{title}</Title>
                    <Label color="blue">{t(typeKey)}</Label>{" "}
                    <Label color={incident.state === "OPEN" ? "red" : "grey"}>
                        {t(incident.state === "OPEN" ? "accessRequestsAdminIncidentsOpen" : "accessRequestsAdminIncidentsClosed")}
                    </Label>
                </DataListCell>,
                <DataListCell key="details" width={4}>
                    <DescriptionList isCompact isHorizontal>
                        <DescriptionListGroup><DescriptionListTerm>{t(notification
                            ? "accessRequestsAdminNotificationDeliveryRecipient"
                            : "accessRequestsAdminFailedProvisioningRequester")}</DescriptionListTerm>
                            <DescriptionListDescription>{person}</DescriptionListDescription></DescriptionListGroup>
                        <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminIncidentsRequest")}</DescriptionListTerm>
                            <DescriptionListDescription>{notification?.requestName ?? title}</DescriptionListDescription></DescriptionListGroup>
                        <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminIncidentsOccurredAt")}</DescriptionListTerm>
                            <DescriptionListDescription>{new Intl.DateTimeFormat(locale,
                                { dateStyle: "medium", timeStyle: "short" }).format(new Date(incident.occurredAt))}
                            </DescriptionListDescription></DescriptionListGroup>
                        {notification && <DescriptionListGroup>
                            <DescriptionListTerm>{t("accessRequestsAdminIncidentsNotificationKind")}</DescriptionListTerm>
                            <DescriptionListDescription>{t(notificationTypeKey(notification.notificationType))}
                            </DescriptionListDescription>
                        </DescriptionListGroup>}
                        {notification && <DescriptionListGroup>
                            <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryRecipientType")}</DescriptionListTerm>
                            <DescriptionListDescription>{t(notification.recipientType === "USER"
                                ? "accessRequestsAdminNotificationRecipientTypeUser"
                                : "accessRequestsAdminNotificationRecipientTypeRealmRole")}</DescriptionListDescription>
                        </DescriptionListGroup>}
                        {cause && <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailureCause")}</DescriptionListTerm>
                            <DescriptionListDescription>{cause}</DescriptionListDescription></DescriptionListGroup>}
                        {provisioning?.closedAt && <>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningClosedAt")}</DescriptionListTerm>
                                <DescriptionListDescription>{new Intl.DateTimeFormat(locale,
                                    { dateStyle: "medium", timeStyle: "short" }).format(new Date(provisioning.closedAt))}
                                </DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningClosedBy")}</DescriptionListTerm>
                                <DescriptionListDescription>{provisioning.closedByName
                                    ?? t("accessRequestsAdminUserUnavailable")}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningCloseReason")}</DescriptionListTerm>
                                <DescriptionListDescription>{provisioning.closureReason}</DescriptionListDescription>
                            </DescriptionListGroup>
                        </>}
                        {revocation?.resolvedAt && <DescriptionListGroup>
                            <DescriptionListTerm>{t("accessRequestsAdminRevocationResolvedAt")}</DescriptionListTerm>
                            <DescriptionListDescription>{new Intl.DateTimeFormat(locale,
                                { dateStyle: "medium", timeStyle: "short" }).format(new Date(revocation.resolvedAt))}
                            </DescriptionListDescription>
                        </DescriptionListGroup>}
                    </DescriptionList>
                </DataListCell>
            ]} />
            {incident.state === "OPEN" && <DataListAction id={`incident-action-${incident.id}`}
                aria-label={t(typeKey)} aria-labelledby={titleId}>
                {notification && <Button variant="secondary" onClick={() => onAction("NOTIFICATION_RETRY", incident)}>
                    {t("accessRequestsAdminNotificationDeliveryRetry")}</Button>}
                {provisioning && <>
                    <Button variant="secondary" onClick={() => onAction("PROVISIONING_RETRY", incident)}>
                        {t("accessRequestsAdminFailedProvisioningRetry")}</Button>
                    <Button variant="link" onClick={() => onAction("PROVISIONING_CLOSE", incident)}>
                        {t("accessRequestsAdminFailedProvisioningClose")}</Button>
                </>}
                {revocation && <>
                    <Button variant="secondary" onClick={() => onAction("REVOCATION_RETRY", incident)}>
                        {t("accessRequestsAdminRevocationRetry")}</Button>
                    <Button variant="link" onClick={() => onAction("REVOCATION_RESOLVE", incident)}>
                        {t("accessRequestsAdminRevocationConfirmRemoved")}</Button>
                </>}
            </DataListAction>}
        </DataListItemRow>
    </DataListItem>;
}

function IncidentNavigation({ page, nextCursor, onPrevious, onNext }: {
    page: number; nextCursor: string | null;
    onPrevious: () => void; onNext: () => void;
}) {
    const { t } = useTranslation();
    return <div className="pf-v5-u-display-flex pf-v5-u-align-items-center pf-v5-u-gap-sm">
        <Button variant="link" isDisabled={page === 0} onClick={onPrevious}>
            {t("accessRequestsAdminIncidentsPrevious")}</Button>
        <span>{t("accessRequestsAdminIncidentsPage", { page: page + 1 })}</span>
        <Button variant="link" isDisabled={!nextCursor} onClick={onNext}>
            {t("accessRequestsAdminIncidentsNext")}</Button>
    </div>;
}

function localDayBoundary(day: string, endOfDay: boolean): string {
    const [year, month, date] = day.split("-").map(Number);
    return new Date(year, month - 1, date, endOfDay ? 23 : 0, endOfDay ? 59 : 0,
        endOfDay ? 59 : 0, endOfDay ? 999 : 0).toISOString();
}

function revocationCodeKey(code: RevocationFailureCode): string {
    return {
        AUTHORITY_UNVERIFIABLE: "accessRequestsAdminRevocationAuthorityUnverifiable",
        REMOVAL_FAILED: "accessRequestsAdminRevocationRemovalFailed",
        UNEXPECTED_FAILURE: "accessRequestsAdminRevocationUnexpectedFailure"
    }[code];
}

function notificationTypeKey(type: NonNullable<AdminIncident["notification"]>["notificationType"]): string {
    return {
        REQUEST_SUBMITTED: "accessRequestsAdminNotificationTypeRequestSubmitted",
        REQUEST_APPROVED: "accessRequestsAdminNotificationTypeRequestApproved",
        REQUEST_REJECTED: "accessRequestsAdminNotificationTypeRequestRejected",
        PROVISIONING_FAILED: "accessRequestsAdminNotificationTypeProvisioningFailed",
        PROVISIONING_CLOSED: "accessRequestsAdminNotificationTypeProvisioningClosed"
    }[type];
}
