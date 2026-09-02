import { useState } from "react";

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
            <h1 id="approvals-title">Approvals</h1>
            {requests.map((request) => (
                <article key={request.id} aria-label={`${request.entitlementName} requested by ${request.requester}`}>
                    <h2>{request.entitlementName}</h2>
                    <p>{request.requester}</p>
                    <p>{request.resourceType}</p>
                    <p>Risk: {request.riskLevel}</p>
                    <p>{request.justification}</p>
                    <p>{request.requestedAt}</p>
                    <button type="button" onClick={() => setPendingDecision({ request, type: "approve" })}>
                        Approve
                    </button>
                    <button type="button" onClick={() => setPendingDecision({ request, type: "reject" })}>
                        Reject
                    </button>
                </article>
            ))}
            {pendingDecision && (
                <div
                    aria-label={`${pendingDecision.type === "approve" ? "Approve" : "Reject"} ${pendingDecision.request.entitlementName}`}
                    aria-modal="true"
                    role="dialog"
                >
                    <h2>{pendingDecision.type === "approve" ? "Approve" : "Reject"}</h2>
                    <label htmlFor="decision-comment">Decision comment</label>
                    <textarea
                        id="decision-comment"
                        onChange={(event) => setComment(event.target.value)}
                        value={comment}
                    />
                    <button type="button" onClick={closeDialog}>
                        Cancel
                    </button>
                    <button type="button" onClick={submit}>
                        {pendingDecision.type === "approve" ? "Confirm approval" : "Confirm rejection"}
                    </button>
                </div>
            )}
        </section>
    );
}
