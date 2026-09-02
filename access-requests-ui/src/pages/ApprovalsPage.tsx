import { useState } from "react";
import { useTranslation } from "react-i18next";

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
};

type PendingDecision = {
    request: PendingApproval;
    type: "approve" | "reject";
};

export function ApprovalsPage({ requests, onApprove, onReject }: ApprovalsPageProps) {
    const { t } = useTranslation();
    const [pendingDecision, setPendingDecision] = useState<PendingDecision>();
    const [comment, setComment] = useState("");

    const closeDialog = () => {
        setPendingDecision(undefined);
        setComment("");
    };

    const submit = () => {
        if (!pendingDecision) {
            return;
        }

        const decision = {
            requestId: pendingDecision.request.id,
            comment: comment.trim()
        };

        if (pendingDecision.type === "approve") {
            void onApprove(decision);
        } else {
            void onReject(decision);
        }
        closeDialog();
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
                <div
                    aria-label={t(
                        pendingDecision.type === "approve"
                            ? "accessRequestsApproveEntitlement"
                            : "accessRequestsRejectEntitlement",
                        { entitlement: pendingDecision.request.entitlementName }
                    )}
                    aria-modal="true"
                    role="dialog"
                >
                    <h2>{t(pendingDecision.type === "approve" ? "accessRequestsApprove" : "accessRequestsReject")}</h2>
                    <label htmlFor="decision-comment">{t("accessRequestsDecisionComment")}</label>
                    <textarea
                        id="decision-comment"
                        onChange={(event) => setComment(event.target.value)}
                        value={comment}
                    />
                    <button type="button" onClick={closeDialog}>
                        {t("accessRequestsCancel")}
                    </button>
                    <button type="button" onClick={submit}>
                        {t(
                            pendingDecision.type === "approve"
                                ? "accessRequestsConfirmApproval"
                                : "accessRequestsConfirmRejection"
                        )}
                    </button>
                </div>
            )}
        </section>
    );
}
