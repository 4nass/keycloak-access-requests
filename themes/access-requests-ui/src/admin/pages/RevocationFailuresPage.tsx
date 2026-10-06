import {
    Alert, Button, DataList, DataListAction, DataListCell, DataListItem, DataListItemCells,
    DataListItemRow, DescriptionList, DescriptionListDescription, DescriptionListGroup,
    DescriptionListTerm, EmptyState, EmptyStateBody, EmptyStateHeader, Modal,
    ModalVariant, PageSection, Pagination, Spinner, Text,
    TextContent, Title, Toolbar, ToolbarContent, ToolbarItem, Form, FormGroup, FormSelect,
    FormSelectOption, TextArea
} from "@patternfly/react-core";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router-dom";
import {
    presentEntitlementsAdminError, type RevocationFailure, type RevocationFailureCode,
    type RevocationFailurePage
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { AccessRequestsAdminTabs } from "./AccessRequestsAdminTabs";

const PAGE_SIZE_OPTIONS = [10, 20, 50].map((value) => ({ title: String(value), value }));

function codeKey(code: RevocationFailureCode) {
    return {
        AUTHORITY_UNVERIFIABLE: "accessRequestsAdminRevocationAuthorityUnverifiable",
        REMOVAL_FAILED: "accessRequestsAdminRevocationRemovalFailed",
        UNEXPECTED_FAILURE: "accessRequestsAdminRevocationUnexpectedFailure"
    }[code];
}

export function RevocationFailuresPage() {
    const { i18n, t } = useTranslation();
    const { pathname } = useLocation();
    const api = useEntitlementsAdminApi();
    const [state, setState] = useState<"OPEN" | "RESOLVED">("OPEN");
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [revision, setRevision] = useState(0);
    const [results, setResults] = useState<RevocationFailurePage>();
    const [loadError, setLoadError] = useState<unknown>();
    const [retryTarget, setRetryTarget] = useState<RevocationFailure>();
    const [retryError, setRetryError] = useState<unknown>();
    const [retrying, setRetrying] = useState(false);
    const [notice, setNotice] = useState<"REVOKED" | "FAILED" | "RESOLVED_EXTERNALLY">();
    const [resolveTarget, setResolveTarget] = useState<RevocationFailure>();
    const [resolveReason, setResolveReason] = useState("");
    const [resolveError, setResolveError] = useState<unknown>();
    const [resolving, setResolving] = useState(false);
    const inFlight = useRef(false);
    const localResolved = useRef(new Set<string>());
    const staleDiagnostics = useRef(new Set<string>());
    const actionRevision = useRef(0);

    useEffect(() => {
        let active = true;
        const actionAtStart = actionRevision.current;
        setLoadError(undefined);
        void api.revocationFailures({ page, size, state }).then((next) => {
            if (!active || actionAtStart !== actionRevision.current) return;
            staleDiagnostics.current.clear();
            const items = state === "OPEN"
                ? next.items.filter((item) => !localResolved.current.has(item.requestId)) : next.items;
            const visible = { ...next, items, total: Math.max(0, next.total - (next.items.length - items.length)) };
            if (page > 0 && !items.length && visible.total > 0) {
                setPage(Math.ceil(visible.total / size) - 1);
                return;
            }
            setResults(visible);
        }).catch((error: unknown) => {
            if (active && actionAtStart === actionRevision.current) setLoadError(error);
        });
        return () => { active = false; };
    }, [api, page, size, state, revision]);

    const retry = async () => {
        if (!retryTarget || inFlight.current) return;
        inFlight.current = true;
        setRetrying(true);
        setRetryError(undefined);
        try {
            const outcome = await api.retryGrantRevocation(retryTarget.requestId);
            actionRevision.current += 1;
            if (outcome.status === "REVOKED") {
                localResolved.current.add(retryTarget.requestId);
                setResults((current) => current && state === "OPEN" ? {
                    ...current,
                    items: current.items.filter((item) => item.requestId !== retryTarget.requestId),
                    total: Math.max(0, current.total - 1)
                } : current);
            } else {
                staleDiagnostics.current.add(retryTarget.requestId);
                setResults((current) => current ? { ...current, items: [...current.items] } : current);
            }
            setNotice(outcome.status);
            setRetryTarget(undefined);
            setRevision((value) => value + 1);
        } catch (error) {
            setRetryError(error);
        } finally {
            inFlight.current = false;
            setRetrying(false);
        }
    };

    const resolve = async () => {
        if (!resolveTarget || inFlight.current || resolveReason.trim().length < 10
            || resolveReason.trim().length > 1000) return;
        inFlight.current = true;
        setResolving(true);
        setResolveError(undefined);
        try {
            await api.resolveGrantRevocation(resolveTarget.requestId, resolveReason.trim());
            actionRevision.current += 1;
            localResolved.current.add(resolveTarget.requestId);
            setResults((current) => current && state === "OPEN" ? {
                ...current,
                items: current.items.filter((item) => item.requestId !== resolveTarget.requestId),
                total: Math.max(0, current.total - 1)
            } : current);
            setNotice("RESOLVED_EXTERNALLY");
            setResolveTarget(undefined);
            setResolveReason("");
            setRevision((value) => value + 1);
        } catch (error) {
            setResolveError(error);
        } finally {
            inFlight.current = false;
            setResolving(false);
        }
    };

    const loadMessage = loadError ? t(presentEntitlementsAdminError(loadError).messageKey) : undefined;
    const retryMessage = retryError ? t(presentEntitlementsAdminError(retryError).messageKey) : undefined;
    const formatDate = (value: string) => new Intl.DateTimeFormat(i18n.language,
        { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
    const requestDetailsBase = pathname.replace(/\/revocation-failures\/?$/, "/requests");

    return <>
        <PageSection variant="light"><Title headingLevel="h1">{t("accessRequestsAdminRevocationFailures")}</Title>
            <TextContent><Text component="p">{t("accessRequestsAdminRevocationFailuresDescription")}</Text></TextContent>
        </PageSection>
        <PageSection>
            <AccessRequestsAdminTabs active="revocations" />
            {notice && <Alert isInline className="pf-v5-u-mb-lg"
                variant={notice === "FAILED" ? "warning" : "success"}
                title={t(notice === "REVOKED" ? "accessRequestsAdminRevocationRetrySuccess"
                    : notice === "RESOLVED_EXTERNALLY" ? "accessRequestsAdminRevocationConfirmedRemoved"
                        : "accessRequestsAdminRevocationRetryFailed")} />}
            {loadMessage && <Alert isInline className="pf-v5-u-mb-lg" variant="danger" title={loadMessage}
                actionLinks={<Button variant="link" onClick={() => setRevision((value) => value + 1)}>{t("reload")}</Button>} />}
            <Toolbar aria-label={t("accessRequestsAdminRevocationFailures")}>
                <ToolbarContent><ToolbarItem>
                    <FormGroup fieldId="revocation-failure-status" label={t("accessRequestsAdminFailureStatus")}>
                        <FormSelect id="revocation-failure-status" value={state} onChange={(_event, next) => {
                            setState(next === "RESOLVED" ? "RESOLVED" : "OPEN");
                            setPage(0); setResults(undefined); setLoadError(undefined); setNotice(undefined);
                        }}>
                            <FormSelectOption value="OPEN" label={t("accessRequestsAdminRevocationOpen")} />
                            <FormSelectOption value="RESOLVED" label={t("accessRequestsAdminRevocationResolved")} />
                        </FormSelect>
                    </FormGroup>
                </ToolbarItem>{results && results.total > 0 && <ToolbarItem align={{ default: "alignRight" }} variant="pagination">
                    <Pagination itemCount={results.total} page={page + 1} perPage={size}
                        perPageOptions={PAGE_SIZE_OPTIONS} widgetId="access-request-revocation-failures"
                        onSetPage={(_event, value) => {
                            setResults(undefined); setLoadError(undefined); setPage(value - 1);
                        }}
                        onPerPageSelect={(_event, value) => {
                            setResults(undefined); setLoadError(undefined); setPage(0); setSize(value);
                        }} />
                </ToolbarItem>}</ToolbarContent>
            </Toolbar>
            {!results && !loadError ? <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
                : results?.items.length ? <DataList aria-label={t("accessRequestsAdminRevocationFailures")}>
                    {results.items.map((item) => {
                        const stale = staleDiagnostics.current.has(item.requestId);
                        const titleId = `revocation-failure-${item.requestId}`;
                        return <DataListItem aria-labelledby={titleId} key={item.requestId}>
                            <DataListItemRow><DataListItemCells dataListCells={[
                                <DataListCell key="request" width={3}>
                                    <Title headingLevel="h3" id={titleId} size="md">{item.entitlementName
                                        ?? item.resourceName ?? t("accessRequestsAdminNotAvailable")}</Title>
                                    <Link to={`${requestDetailsBase}/${encodeURIComponent(item.requestId)}`}>
                                        {t("accessRequestsAdminEventsViewRequest")}
                                    </Link>
                                </DataListCell>,
                                <DataListCell key="details" width={3}><DescriptionList isCompact isHorizontal>
                                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningRequester")}</DescriptionListTerm>
                                        <DescriptionListDescription>{item.requesterName
                                            ?? t("accessRequestsAdminUserUnavailable")}</DescriptionListDescription></DescriptionListGroup>
                                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsRequest")}</DescriptionListTerm>
                                        <DescriptionListDescription><Text component="small">{item.requestId}</Text></DescriptionListDescription></DescriptionListGroup>
                                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningEntitlement")}</DescriptionListTerm>
                                        <DescriptionListDescription>{item.entitlementName
                                            ?? t("accessRequestsAdminNotAvailable")}</DescriptionListDescription></DescriptionListGroup>
                                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailureStatus")}</DescriptionListTerm>
                                        <DescriptionListDescription>{t(state === "OPEN"
                                            ? "accessRequestsAdminFailureOpen" : "accessRequestsAdminFailureResolved")}</DescriptionListDescription></DescriptionListGroup>
                                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminRevocationExpiredAt")}</DescriptionListTerm>
                                        <DescriptionListDescription>{item.expiresAt
                                            ? formatDate(item.expiresAt) : t("accessRequestsAdminPermanent")}</DescriptionListDescription></DescriptionListGroup>
                                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailureCause")}</DescriptionListTerm>
                                        <DescriptionListDescription>{stale ? t("accessRequestsAdminRevocationDiagnosticPending")
                                            : t(codeKey(item.failureCode))}</DescriptionListDescription></DescriptionListGroup>
                                    {!stale && <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminRevocationAttempts")}</DescriptionListTerm>
                                        <DescriptionListDescription>{item.attemptCount}</DescriptionListDescription></DescriptionListGroup>}
                                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminRevocationFirstFailedAt")}</DescriptionListTerm>
                                        <DescriptionListDescription>{formatDate(item.firstFailedAt)}</DescriptionListDescription></DescriptionListGroup>
                                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminRevocationLastFailedAt")}</DescriptionListTerm>
                                        <DescriptionListDescription>{formatDate(item.lastFailedAt)}</DescriptionListDescription></DescriptionListGroup>
                                    {state === "OPEN" && !stale && item.expiresAt && <DescriptionListGroup>
                                        <DescriptionListTerm>{t("accessRequestsAdminRevocationNextAttempt")}</DescriptionListTerm>
                                        <DescriptionListDescription>{formatDate(item.nextAttemptAt)}</DescriptionListDescription>
                                    </DescriptionListGroup>}
                                    {state === "OPEN" && !stale && !item.expiresAt && <DescriptionListGroup>
                                        <DescriptionListTerm>{t("accessRequestsAdminRevocationNextAttempt")}</DescriptionListTerm>
                                        <DescriptionListDescription>{t("accessRequestsAdminRevocationManualRetryOnly")}</DescriptionListDescription>
                                    </DescriptionListGroup>}
                                    {item.resolvedAt && <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminRevocationResolvedAt")}</DescriptionListTerm>
                                        <DescriptionListDescription>{formatDate(item.resolvedAt)}</DescriptionListDescription></DescriptionListGroup>}
                                </DescriptionList></DataListCell>
                            ]} />
                                {state === "OPEN" && <DataListAction aria-label={t("accessRequestsAdminRevocationRetry")}
                                    aria-labelledby={titleId} id={`revocation-action-${item.requestId}`}>
                                    <Button variant="secondary" onClick={() => { setRetryTarget(item); setRetryError(undefined); setNotice(undefined); }}>
                                        {t("accessRequestsAdminRevocationRetry")}</Button>
                                    <Button variant="link" onClick={() => {
                                        setResolveTarget(item); setResolveReason(""); setResolveError(undefined); setNotice(undefined);
                                    }}>{t("accessRequestsAdminRevocationConfirmRemoved")}</Button>
                                </DataListAction>}
                            </DataListItemRow>
                        </DataListItem>;
                    })}
                </DataList> : results ? <EmptyState><EmptyStateHeader headingLevel="h2"
                    titleText={t(state === "OPEN" ? "accessRequestsAdminRevocationOpenEmpty"
                        : "accessRequestsAdminRevocationResolvedEmpty")} />
                    <EmptyStateBody>{t("accessRequestsAdminRevocationFailuresDescription")}</EmptyStateBody>
                </EmptyState> : null}
        </PageSection>
        {retryTarget && <Modal isOpen variant={ModalVariant.small} title={t("accessRequestsAdminRevocationRetry")}
            onClose={() => { if (!inFlight.current) setRetryTarget(undefined); }}
            actions={[
                <Button key="retry" isDisabled={retrying} isLoading={retrying} onClick={() => void retry()}>
                    {t("accessRequestsAdminRevocationRetry")}</Button>,
                <Button key="cancel" variant="link" isDisabled={retrying} onClick={() => setRetryTarget(undefined)}>
                    {t("accessRequestsAdminCancel")}</Button>
            ]}>
            {retryMessage && <Alert isInline variant="danger" title={retryMessage} />}
            <Alert isInline variant="warning" title={t("accessRequestsAdminRevocationRetryWarning")} />
            <TextContent><Text component="p">{retryTarget.entitlementName ?? retryTarget.resourceName ?? t("accessRequestsAdminNotAvailable")} — {retryTarget.requesterName ?? t("accessRequestsAdminUserUnavailable")}</Text>
                <Text component="small">{t("accessRequestsAdminEventsRequest")}: {retryTarget.requestId}</Text></TextContent>
        </Modal>}
        {resolveTarget && <Modal isOpen variant={ModalVariant.small}
            title={t("accessRequestsAdminRevocationConfirmRemoved")}
            onClose={() => { if (!inFlight.current) setResolveTarget(undefined); }}
            actions={[
                <Button key="resolve" isDisabled={resolving || resolveReason.trim().length < 10}
                    isLoading={resolving} onClick={() => void resolve()}>
                    {t("accessRequestsAdminRevocationConfirmRemoved")}</Button>,
                <Button key="cancel" variant="link" isDisabled={resolving}
                    onClick={() => setResolveTarget(undefined)}>{t("accessRequestsAdminCancel")}</Button>
            ]}>
            {resolveError ? <Alert isInline variant="danger"
                title={t(presentEntitlementsAdminError(resolveError).messageKey)} /> : null}
            <Alert isInline variant="warning" title={t("accessRequestsAdminRevocationConfirmRemovedWarning")} />
            <Form onSubmit={(event) => { event.preventDefault(); void resolve(); }}>
                <FormGroup fieldId="revocation-resolution-reason" isRequired
                    label={t("accessRequestsAdminRevocationResolutionReason")}>
                    <TextArea id="revocation-resolution-reason" maxLength={1000} value={resolveReason}
                        onChange={(_event, value) => setResolveReason(value)} />
                </FormGroup>
            </Form>
        </Modal>}
    </>;
}
