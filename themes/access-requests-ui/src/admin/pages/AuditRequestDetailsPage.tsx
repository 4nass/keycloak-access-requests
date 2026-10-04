import {
    Alert, Button, DataList, DataListCell, DataListItem, DataListItemCells, DataListItemRow,
    DescriptionList, DescriptionListDescription, DescriptionListGroup, DescriptionListTerm,
    EmptyState, Form, FormGroup, Label, Modal, ModalVariant, Pagination, Spinner, TextArea, Title
} from "@patternfly/react-core";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";

import { presentEntitlementsAdminError, type AdminAuditRequestDetails } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { auditEventTypes } from "./auditEventTypes";
import { adminActorLabel } from "./adminActorLabel";
import { failureCodeKey } from "./failureCodePresentation";

export function AuditRequestDetailsPage() {
    const { t, i18n } = useTranslation();
    const api = useEntitlementsAdminApi();
    const navigate = useNavigate();
    const { realm, requestId } = useParams();
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [details, setDetails] = useState<{
        api: typeof api; requestId: string; page: number; size: number; data: AdminAuditRequestDetails
    }>();
    const [error, setError] = useState<{
        api: typeof api; requestId: string; page: number; size: number; cause: unknown
    }>();
    const [retry, setRetry] = useState(0);
    const [revokeMode, setRevokeMode] = useState(false);
    const [revokeReason, setRevokeReason] = useState("");
    const [revoking, setRevoking] = useState(false);
    const revocationInFlight = useRef(false);
    const [revokeError, setRevokeError] = useState<unknown>();
    const [revokeNotice, setRevokeNotice] = useState<"REVOKED" | "FAILED">();

    useEffect(() => {
        if (!requestId) return;
        let active = true;
        setError(undefined);
        void api.auditRequest(requestId, { page, size }).then((next) => {
            if (active) setDetails({ api, requestId, page, size, data: next });
        }).catch((failure: unknown) => {
            if (active) setError({ api, requestId, page, size, cause: failure });
        });
        return () => { active = false; };
    }, [api, requestId, page, size, retry]);

    const currentDetails = details?.api === api && details.requestId === requestId
        && details.page === page && details.size === size ? details.data : undefined;
    const currentError = error?.api === api && error.requestId === requestId
        && error.page === page && error.size === size ? error.cause : undefined;
    const errorPresentation = currentError ? presentEntitlementsAdminError(currentError) : undefined;
    const formatDate = (date: string) => new Intl.DateTimeFormat(i18n.resolvedLanguage || "en", {
        dateStyle: "medium", timeStyle: "short"
    }).format(new Date(date));
    const close = () => navigate(`/${encodeURIComponent(realm ?? "")}/access-requests/events`, { replace: true });
    const revoke = async () => {
        if (!requestId || revocationInFlight.current
            || revokeReason.trim().length < 10 || revokeReason.trim().length > 1000) return;
        revocationInFlight.current = true;
        setRevoking(true);
        setRevokeError(undefined);
        try {
            const result = await api.revokeGrant(requestId, revokeReason.trim());
            setRevokeNotice(result.status);
            if (result.status === "REVOKED") {
                setDetails((previous) => previous?.requestId === requestId ? {
                    ...previous, data: { ...previous.data,
                        grant: previous.data.grant ? { ...previous.data.grant,
                            manuallyRevocable: false, revocationState: "REVOKED" } : null }
                } : previous);
            }
            setRevokeMode(false);
            setRetry((value) => value + 1);
        } catch (failure) {
            setRevokeError(failure);
        } finally {
            revocationInFlight.current = false;
            setRevoking(false);
        }
    };

    return <Modal isOpen onClose={() => { if (!revoking) close(); }} variant={ModalVariant.large}
        title={`${t("accessRequestsAdminEventsRequestTitle")}${currentDetails
            ? `: ${currentDetails.entitlementName ?? currentDetails.resourceName}` : ""}`}
        actions={revokeMode ? [
            <Button key="revoke" variant="danger" isLoading={revoking} isDisabled={revoking || revokeReason.trim().length < 10}
                onClick={() => void revoke()}>{t("accessRequestsAdminRevokeAccess")}</Button>,
            <Button key="back" variant="link" isDisabled={revoking} onClick={() => setRevokeMode(false)}>
                {t("accessRequestsAdminCancel")}</Button>
        ] : [
            ...(currentDetails?.grant?.manuallyRevocable ? [<Button key="revoke" variant="danger"
                onClick={() => { setRevokeReason(""); setRevokeError(undefined); setRevokeNotice(undefined); setRevokeMode(true); }}>
                {t("accessRequestsAdminRevokeAccess")}</Button>] : []),
            <Button key="close" onClick={close}>{t("accessRequestsAdminEventsClose")}</Button>
        ]}>
            {revokeMode && <>
                <Alert isInline variant="warning" title={t("accessRequestsAdminRevokeAccessWarning")} />
                {revokeError && <Alert isInline variant="danger"
                    title={t(presentEntitlementsAdminError(revokeError).messageKey)} />}
                <Form onSubmit={(event) => { event.preventDefault(); void revoke(); }}>
                    <FormGroup fieldId="grant-revocation-reason" isRequired
                        label={t("accessRequestsAdminRevocationResolutionReason")}>
                        <TextArea id="grant-revocation-reason" autoFocus maxLength={1000} value={revokeReason}
                            onChange={(_event, value) => setRevokeReason(value)} />
                    </FormGroup>
                </Form>
            </>}
            {!revokeMode && revokeNotice && <Alert isInline
                variant={revokeNotice === "REVOKED" ? "success" : "danger"}
                title={t(revokeNotice === "REVOKED" ? "accessRequestsAdminRevokeAccessSuccess"
                    : "accessRequestsAdminRevocationRetryFailed")} />}
            {!revokeMode && <>
            {errorPresentation && <Alert isInline variant="danger"
                title={errorPresentation.requestId
                    ? `${t(errorPresentation.messageKey)} (${errorPresentation.requestId})`
                    : t(errorPresentation.messageKey)}
                actionLinks={<Button variant="link" onClick={() => setRetry((value) => value + 1)}>{t("reload")}</Button>}
            />}
            {!currentDetails && !errorPresentation && <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>}
            {currentDetails && <>
                <DescriptionList isCompact columnModifier={{ default: "1Col", md: "2Col", lg: "3Col" }}>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningRequester")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.requesterName
                            ?? t("accessRequestsAdminUserUnavailable")}</DescriptionListDescription></DescriptionListGroup>
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningEntitlement")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.entitlementName
                            ?? currentDetails.resourceName}</DescriptionListDescription></DescriptionListGroup>
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
                    {currentDetails.grant && <DescriptionListGroup>
                        <DescriptionListTerm>{t("accessRequestsAdminGrantExpiry")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.grant.expiresAt
                            ? formatDate(currentDetails.grant.expiresAt) : t("accessRequestsAdminPermanent")}
                        </DescriptionListDescription></DescriptionListGroup>}
                    <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsJustification")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.justification}</DescriptionListDescription></DescriptionListGroup>
                    {currentDetails.decision && <DescriptionListGroup><DescriptionListTerm>{t("accessRequestsAdminEventsActor")}</DescriptionListTerm>
                        <DescriptionListDescription>{currentDetails.approverName
                            ?? t("accessRequestsAdminUserUnavailable")}: {currentDetails.decision.comment}</DescriptionListDescription></DescriptionListGroup>}
                </DescriptionList>
                <Title headingLevel="h3" size="lg" className="pf-v5-u-mt-lg">
                    {t("accessRequestsAdminEventsHistory")}</Title>
                <DataList aria-label={t("accessRequestsAdminEventsHistory")}>
                    {currentDetails.history.map((event, index) => <DataListItem key={`${event.type}-${event.occurredAt}-${index}`}>
                        <DataListItemRow><DataListItemCells dataListCells={[
                            <DataListCell key="type">{t(auditEventTypes[event.type])}</DataListCell>,
                            <DataListCell key="date">{formatDate(event.occurredAt)}</DataListCell>,
                            <DataListCell key="actor">{t("accessRequestsAdminEventsActor")}: {adminActorLabel(
                                event.actorId, event.actorName, t)}</DataListCell>,
                            ...(event.failureCode ? [<DataListCell key="failure-code">
                                {t("accessRequestsAdminFailureCause")}: {t(failureCodeKey(event.failureCode))}
                            </DataListCell>] : []),
                            ...(event.revocationFailureCode ? [<DataListCell key="revocation-failure-code">
                                {t("accessRequestsAdminFailureCause")}: {t({
                                    AUTHORITY_UNVERIFIABLE: "accessRequestsAdminRevocationAuthorityUnverifiable",
                                    REMOVAL_FAILED: "accessRequestsAdminRevocationRemovalFailed",
                                    UNEXPECTED_FAILURE: "accessRequestsAdminRevocationUnexpectedFailure"
                                }[event.revocationFailureCode])}
                            </DataListCell>] : []),
                            ...(event.revocationResolutionReason ? [<DataListCell key="revocation-resolution-reason">
                                {t("accessRequestsAdminRevocationResolutionReason")}: {event.revocationResolutionReason}
                            </DataListCell>] : []),
                            ...(event.closureReason ? [<DataListCell key="closure-reason">
                                {t("accessRequestsAdminFailedProvisioningCloseReason")}: {event.closureReason}
                            </DataListCell>] : [])
                        ]} /></DataListItemRow>
                    </DataListItem>)}
                </DataList>
                {currentDetails.historyTotal > currentDetails.historySize && <Pagination
                    aria-label={t("accessRequestsAdminEventsHistory")}
                    itemCount={currentDetails.historyTotal}
                    page={page + 1}
                    perPage={size}
                    perPageOptions={[10, 20, 50].map((value) => ({ title: String(value), value }))}
                    onSetPage={(_event, next) => setPage(next - 1)}
                    onPerPageSelect={(_event, next) => { setPage(0); setSize(next); }}
                />}
            </>}
            </>}
    </Modal>;
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
