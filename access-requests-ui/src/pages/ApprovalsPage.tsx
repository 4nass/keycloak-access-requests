import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";

import { AccessibleDialog } from "./AccessibleDialog";

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
    onReject: (decision: ApprovalDecision) => void | Promise<void>;
    onRefresh?: () => void | Promise<void>;
};

type PendingDecision = {
    request: PendingApproval;
    type: "approve" | "reject";
};

function errorMessage(error: unknown) {
    return error instanceof Error ? error.message : String(error);
}

export function ApprovalsPage({ requests, onApprove, onReject, onRefresh }: ApprovalsPageProps) {
    const { t } = useTranslation();
    const [pendingDecision, setPendingDecision] = useState<PendingDecision>();
    const [comment, setComment] = useState("");
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [decisionError, setDecisionError] = useState<string>();
    const decisionCommentRef = useRef<HTMLTextAreaElement>(null);

    const closeDialog = () => {
        setPendingDecision(undefined);
        setComment("");
        setDecisionError(undefined);
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
        setDecisionError(undefined);

        try {
            if (pendingDecision.type === "approve") {
                await onApprove(decision);
            } else {
                await onReject(decision);
            }
            closeDialog();
            await onRefresh?.();
        } catch (error) {
            setDecisionError(errorMessage(error));
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <section aria-labelledby="approvals-title">
            <h1 id="approvals-title">{t("accessRequestsApprovals")}</h1>
            {requests.map((request) => (
                <article
                    key={request.id}
                    aria-label={t("accessRequestsRequestedBy", {
                        entitlement: request.entitlementName,
                        requester: request.requester
                    })}
                >
                    <h2>{request.entitlementName}</h2>
                    <p>{request.requester}</p>
                    <p>{request.resourceType}</p>
                    <p>{t("accessRequestsRisk", { riskLevel: request.riskLevel })}</p>
                    <p>{request.justification}</p>
                    <p>{request.requestedAt}</p>
                    <button type="button" onClick={() => setPendingDecision({ request, type: "approve" })}>
                        {t("accessRequestsApprove")}
                    </button>
                    <button type="button" onClick={() => setPendingDecision({ request, type: "reject" })}>
                        {t("accessRequestsReject")}
                    </button>
                </article>
            ))}
            {pendingDecision && (
                <AccessibleDialog
                    ariaLabel={t(
                        pendingDecision.type === "approve"
                            ? "accessRequestsApproveEntitlement"
                            : "accessRequestsRejectEntitlement",
                        { entitlement: pendingDecision.request.entitlementName }
                    )}
                    initialFocusRef={decisionCommentRef}
                    onClose={closeDialog}
                >
                    <h2>{t(pendingDecision.type === "approve" ? "accessRequestsApprove" : "accessRequestsReject")}</h2>
                    <label htmlFor="decision-comment">{t("accessRequestsDecisionComment")}</label>
                    <textarea
                        id="decision-comment"
                        onChange={(event) => setComment(event.target.value)}
                        ref={decisionCommentRef}
                        value={comment}
                    />
                    {decisionError && <p role="alert">{decisionError}</p>}
                    <button type="button" disabled={isSubmitting} onClick={closeDialog}>
                        {t("accessRequestsCancel")}
                    </button>
                    <button type="button" disabled={isSubmitting} onClick={() => void submit()}>
                        {t(
                            pendingDecision.type === "approve"
                                ? "accessRequestsConfirmApproval"
                                : "accessRequestsConfirmRejection"
                        )}
                    </button>
                </AccessibleDialog>
            )}
        </section>
    );
}
