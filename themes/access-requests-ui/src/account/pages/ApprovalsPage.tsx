import { Page } from "@keycloak/keycloak-account-ui";
import {
    ActionGroup,
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
    Form,
    FormGroup,
    Modal,
    TextArea
} from "@patternfly/react-core";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import { AccessRequestEmptyState } from "./AccessRequestEmptyState";
import { AccessRequestPagination, type AccessRequestPaginationState } from "./AccessRequestPagination";
import { RiskLevelLabel, formatDateTime, resourceTypeLabel } from "./AccessRequestPresentation";
import { useAccessRequestAlerts } from "./useAccessRequestAlerts";

export type PendingApproval = {
    id: string;
    requester: string;
    entitlementName: string;
    resourceType: string;
    riskLevel: string;
    justification: string;
    requestedAt: string;
};

type ApprovalDecision = {
    requestId: string;
    comment: string;
};

type ApprovalsPageProps = {
    requests: PendingApproval[];
    onApprove: (decision: ApprovalDecision) => void | Promise<void>;
    pagination?: AccessRequestPaginationState;
    onReject: (decision: ApprovalDecision) => void | Promise<void>;
    onRefresh?: () => void | Promise<void>;
};

type PendingDecision = {
    request: PendingApproval;
    type: "approve" | "reject";
};

export function ApprovalsPage({ requests, onApprove, onReject, onRefresh, pagination }: ApprovalsPageProps) {
    const { i18n, t } = useTranslation();
    const { addAlert, addError } = useAccessRequestAlerts();
    const [pendingDecision, setPendingDecision] = useState<PendingDecision>();
    const [comment, setComment] = useState("");
    const [isSubmitting, setIsSubmitting] = useState(false);

    const closeDialog = () => {
        setPendingDecision(undefined);
        setComment("");
    };

    const dismissDialog = () => {
        if (!isSubmitting) {
            closeDialog();
        }
    };

    const submit = async () => {
        if (!pendingDecision || isSubmitting) {
            return;
        }

        const decision = {
            requestId: pendingDecision.request.id,
            comment: comment.trim()
        };

        setIsSubmitting(true);

        try {
            if (pendingDecision.type === "approve") {
                await onApprove(decision);
            } else {
                await onReject(decision);
            }
        } catch (error) {
            addError(error);
            setIsSubmitting(false);
            return;
        }

        addAlert(t(
            pendingDecision.type === "approve" ? "accessRequestsRequestApproved" : "accessRequestsRequestRejected"
        ));
        closeDialog();
        try {
            await onRefresh?.();
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <Page description={t("accessRequestsApprovalsDescription")} title={t("accessRequestsApprovals")}>
            <>
                <AccessRequestPagination pagination={pagination} />
                {requests.length === 0 ? (
                    <AccessRequestEmptyState
                        description={t("accessRequestsNoApprovalsDescription")}
                        title={t("accessRequestsNoApprovals")}
                    />
                ) : (
                    <DataList aria-label={t("accessRequestsApprovals")}>
                        {requests.map((request) => {
                            const requestedById = `pending-request-${request.id}-requested-by`;
                            const titleId = `pending-request-${request.id}-title`;
                            return (
                                <DataListItem aria-labelledby={requestedById} id={`pending-request-${request.id}`} key={request.id}>
                                    <DataListItemRow>
                                        <DataListItemCells
                                            dataListCells={[
                                                <DataListCell key="request" width={3}>
                                                    <span className="pf-v5-screen-reader" id={requestedById}>
                                                        {t("accessRequestsRequestedBy", {
                                                            entitlement: request.entitlementName,
                                                            requester: request.requester
                                                        })}
                                                    </span>
                                                    <strong id={titleId}>{request.entitlementName}</strong>
                                                    <p>{request.requester}</p>
                                                    <p>{request.justification}</p>
                                                </DataListCell>,
                                                <DataListCell key="attributes" width={2}>
                                                    <DescriptionList isCompact>
                                                        <DescriptionListGroup>
                                                            <DescriptionListTerm>{t("accessRequestsResourceType")}</DescriptionListTerm>
                                                            <DescriptionListDescription>
                                                                {resourceTypeLabel(request.resourceType, t)}
                                                            </DescriptionListDescription>
                                                        </DescriptionListGroup>
                                                        <DescriptionListGroup>
                                                            <DescriptionListTerm>{t("accessRequestsRiskLabel")}</DescriptionListTerm>
                                                            <DescriptionListDescription>
                                                                <RiskLevelLabel riskLevel={request.riskLevel} t={t} />
                                                            </DescriptionListDescription>
                                                        </DescriptionListGroup>
                                                        <DescriptionListGroup>
                                                            <DescriptionListTerm>{t("accessRequestsRequestedAt")}</DescriptionListTerm>
                                                            <DescriptionListDescription>
                                                                <time dateTime={request.requestedAt}>
                                                                    {formatDateTime(request.requestedAt, i18n.resolvedLanguage ?? i18n.language)}
                                                                </time>
                                                            </DescriptionListDescription>
                                                        </DescriptionListGroup>
                                                    </DescriptionList>
                                                </DataListCell>
                                            ]}
                                        />
                                        <DataListAction
                                            aria-label={t("accessRequestsRequestedBy", {
                                                entitlement: request.entitlementName,
                                                requester: request.requester
                                            })}
                                            aria-labelledby={titleId}
                                            id={`pending-request-${request.id}-actions`}
                                        >
                                            <Button
                                                type="button"
                                                variant="primary"
                                                onClick={() => setPendingDecision({ request, type: "approve" })}
                                            >
                                                {t("accessRequestsApprove")}
                                            </Button>
                                            <Button
                                                type="button"
                                                variant="link"
                                                onClick={() => setPendingDecision({ request, type: "reject" })}
                                            >
                                                {t("accessRequestsReject")}
                                            </Button>
                                        </DataListAction>
                                    </DataListItemRow>
                                </DataListItem>
                            );
                        })}
                    </DataList>
                )}
            {pendingDecision && (
                <Modal
                    elementToFocus="#access-request-decision-comment"
                    isOpen
                    onClose={dismissDialog}
                    showClose={!isSubmitting}
                    title={t(
                        pendingDecision.type === "approve"
                            ? "accessRequestsApproveEntitlement"
                            : "accessRequestsRejectEntitlement",
                        { entitlement: pendingDecision.request.entitlementName }
                    )}
                    variant="small"
                >
                    <Form
                        onSubmit={(event) => {
                            event.preventDefault();
                            void submit();
                        }}
                    >
                        <FormGroup fieldId="access-request-decision-comment" label={t("accessRequestsDecisionComment")}>
                            <TextArea
                                aria-label={t("accessRequestsDecisionComment")}
                                id="access-request-decision-comment"
                                isDisabled={isSubmitting}
                                onChange={(_, value) => setComment(value)}
                                value={comment}
                            />
                        </FormGroup>
                        <ActionGroup>
                            <Button
                                isDisabled={isSubmitting}
                                type="submit"
                                variant={pendingDecision.type === "approve" ? "primary" : "danger"}
                            >
                                {t(
                                    pendingDecision.type === "approve"
                                        ? "accessRequestsConfirmApproval"
                                        : "accessRequestsConfirmRejection"
                                )}
                            </Button>
                            <Button isDisabled={isSubmitting} type="button" variant="link" onClick={dismissDialog}>
                                {t("accessRequestsCancel")}
                            </Button>
                        </ActionGroup>
                    </Form>
                </Modal>
            )}
            </>
        </Page>
    );
}
