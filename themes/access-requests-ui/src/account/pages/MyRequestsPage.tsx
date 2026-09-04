import { Page } from "@keycloak/keycloak-account-ui";
import { ContinueCancelModal } from "@keycloak/keycloak-ui-shared";
import {
    Alert,
    Button,
    DataList,
    DataListAction,
    DataListCell,
    DataListItem,
    DataListItemCells,
    DataListItemRow,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    LabelGroup,
    Modal
} from "@patternfly/react-core";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import { presentAccessRequestsError, type AccessRequestsErrorPresentation } from "../api/AccessRequestsApi";
import { AccessRequestEmptyState } from "./AccessRequestEmptyState";
import { AccessRequestPagination, type AccessRequestPaginationState } from "./AccessRequestPagination";
import {
    DecisionStatusLabel,
    HistoryEventLabel,
    ProvisioningStatusLabel,
    formatDateTime,
    resourceTypeLabel
} from "./AccessRequestPresentation";
import { useAccessRequestAlerts } from "./useAccessRequestAlerts";

type RequestHistoryEntry = {
    type: string;
    occurredAt: string;
};

type RequestDecision = {
    decidedAt: string;
    approver: string;
    comment: string;
};

export type AccessRequest = {
    id: string;
    entitlementName: string;
    resourceType: string;
    decisionStatus: string;
    provisioningStatus: string;
    requestedAt: string;
    justification: string;
    decision?: RequestDecision;
    history: RequestHistoryEntry[];
};

export type AccessRequestDetails = Pick<AccessRequest, "justification" | "decision" | "history">;

type MyRequestsPageProps = {
    requests: AccessRequest[];
    onCancel: (requestId: string) => void | Promise<void>;
    onRequestDetails?: (requestId: string) => Promise<AccessRequestDetails>;
    pagination?: AccessRequestPaginationState;
    onRefresh?: () => void | Promise<void>;
};

export function MyRequestsPage({ requests, onCancel, onRequestDetails, onRefresh, pagination }: MyRequestsPageProps) {
    const { i18n, t } = useTranslation();
    const { addAlert, addError } = useAccessRequestAlerts();
    const [selectedRequest, setSelectedRequest] = useState<AccessRequest>();
    const [cancellingRequestId, setCancellingRequestId] = useState<string>();
    const [detailsError, setDetailsError] = useState<AccessRequestsErrorPresentation>();
    const [isLoadingDetails, setIsLoadingDetails] = useState(false);

    const cancel = async (requestId: string) => {
        if (cancellingRequestId) {
            return;
        }

        setCancellingRequestId(requestId);

        try {
            await onCancel(requestId);
        } catch (error) {
            addError(error);
            setCancellingRequestId(undefined);
            return;
        }

        addAlert(t("accessRequestsRequestCanceled"));
        try {
            await onRefresh?.();
        } finally {
            setCancellingRequestId(undefined);
        }
    };

    const openDetails = async (request: AccessRequest) => {
        setSelectedRequest(request);
        setDetailsError(undefined);
        if (!onRequestDetails) {
            return;
        }

        setIsLoadingDetails(true);
        try {
            const details = await onRequestDetails(request.id);
            setSelectedRequest((selected) => selected && selected.id === request.id ? { ...selected, ...details } : selected);
        } catch (error) {
            setDetailsError(presentAccessRequestsError(error));
        } finally {
            setIsLoadingDetails(false);
        }
    };

    return (
        <Page description={t("accessRequestsMyRequestsDescription")} title={t("accessRequestsMyRequests")}>
            <>
                <AccessRequestPagination pagination={pagination} />
                {requests.length === 0 ? (
                    <AccessRequestEmptyState
                        description={t("accessRequestsNoRequestsDescription")}
                        title={t("accessRequestsNoRequests")}
                    />
                ) : (
                    <DataList aria-label={t("accessRequestsMyRequests")}>
                        {requests.map((request) => {
                            const titleId = `access-request-${request.id}-title`;
                            return (
                                <DataListItem aria-labelledby={titleId} id={`access-request-${request.id}`} key={request.id}>
                                    <DataListItemRow>
                                        <DataListItemCells
                                            dataListCells={[
                                                <DataListCell key="request" width={3}>
                                                    <strong id={titleId}>{request.entitlementName}</strong>
                                                    <p>{resourceTypeLabel(request.resourceType, t)}</p>
                                                </DataListCell>,
                                                <DataListCell key="status" width={2}>
                                                    <LabelGroup aria-label={t("accessRequestsStatus")} isCompact>
                                                        <DecisionStatusLabel status={request.decisionStatus} t={t} />
                                                        {request.decisionStatus === "APPROVED" && (
                                                            <ProvisioningStatusLabel status={request.provisioningStatus} t={t} />
                                                        )}
                                                    </LabelGroup>
                                                    <p>
                                                        <time dateTime={request.requestedAt}>
                                                            {formatDateTime(request.requestedAt, i18n.resolvedLanguage ?? i18n.language)}
                                                        </time>
                                                    </p>
                                                </DataListCell>
                                            ]}
                                        />
                                        <DataListAction
                                            aria-label={t("accessRequestsRequestDetails", { entitlement: request.entitlementName })}
                                            aria-labelledby={titleId}
                                            id={`access-request-${request.id}-actions`}
                                        >
                                            {request.decisionStatus === "PENDING" && (
                                                <ContinueCancelModal
                                                    buttonTitle={t("accessRequestsCancelRequest")}
                                                    buttonVariant="link"
                                                    cancelLabel={t("accessRequestsCancel")}
                                                    continueLabel={t("accessRequestsCancelRequest")}
                                                    isDisabled={cancellingRequestId === request.id}
                                                    modalTitle={t("accessRequestsCancelRequest")}
                                                    onContinue={() => void cancel(request.id)}
                                                >
                                                    <p>{t("accessRequestsCancelRequestDescription")}</p>
                                                </ContinueCancelModal>
                                            )}
                                            <Button type="button" variant="secondary" onClick={() => void openDetails(request)}>
                                                {t("accessRequestsViewDetails")}
                                            </Button>
                                        </DataListAction>
                                    </DataListItemRow>
                                </DataListItem>
                            );
                        })}
                    </DataList>
                )}
            {selectedRequest && (
                <Modal
                    actions={[
                        <Button key="close" type="button" variant="primary" onClick={() => setSelectedRequest(undefined)}>
                            {t("accessRequestsClose")}
                        </Button>
                    ]}
                    isOpen
                    onClose={() => setSelectedRequest(undefined)}
                    title={t("accessRequestsRequestDetails", { entitlement: selectedRequest.entitlementName })}
                    variant="medium"
                >
                    {isLoadingDetails ? (
                        <p>{t("accessRequestsLoading")}</p>
                    ) : detailsError ? (
                        <Alert isInline role="alert" title={t(detailsError.messageKey)} variant="danger">
                            {detailsError.requestId && t("accessRequestsErrorReference", { requestId: detailsError.requestId })}
                        </Alert>
                    ) : (
                        <>
                            <DescriptionList isAutoFit isCompact>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>{t("accessRequestsJustification")}</DescriptionListTerm>
                                    <DescriptionListDescription>{selectedRequest.justification}</DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>{t("accessRequestsDecision")}</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        <DecisionStatusLabel status={selectedRequest.decisionStatus} t={t} />
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>{t("accessRequestsProvisioning")}</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        <ProvisioningStatusLabel status={selectedRequest.provisioningStatus} t={t} />
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                {selectedRequest.decision && (
                                    <>
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>{t("accessRequestsApprover")}</DescriptionListTerm>
                                            <DescriptionListDescription>{selectedRequest.decision.approver}</DescriptionListDescription>
                                        </DescriptionListGroup>
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>{t("accessRequestsDecidedAt")}</DescriptionListTerm>
                                            <DescriptionListDescription>
                                                <time dateTime={selectedRequest.decision.decidedAt}>
                                                    {formatDateTime(
                                                        selectedRequest.decision.decidedAt,
                                                        i18n.resolvedLanguage ?? i18n.language
                                                    )}
                                                </time>
                                            </DescriptionListDescription>
                                        </DescriptionListGroup>
                                        {selectedRequest.decision.comment && (
                                            <DescriptionListGroup>
                                                <DescriptionListTerm>{t("accessRequestsDecisionComment")}</DescriptionListTerm>
                                                <DescriptionListDescription>{selectedRequest.decision.comment}</DescriptionListDescription>
                                            </DescriptionListGroup>
                                        )}
                                    </>
                                )}
                            </DescriptionList>
                            <h3>{t("accessRequestsHistory")}</h3>
                            <ol>
                                {selectedRequest.history.map((event) => (
                                    <li key={`${event.type}-${event.occurredAt}`}>
                                        <HistoryEventLabel event={event.type} t={t} /> {" "}
                                        <time dateTime={event.occurredAt}>
                                            {formatDateTime(event.occurredAt, i18n.resolvedLanguage ?? i18n.language)}
                                        </time>
                                    </li>
                                ))}
                            </ol>
                        </>
                    )}
                </Modal>
            )}
            </>
        </Page>
    );
}
