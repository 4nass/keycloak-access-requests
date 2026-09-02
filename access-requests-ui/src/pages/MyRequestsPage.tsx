import { useState } from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";

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

type MyRequestsPageProps = {
    requests: AccessRequest[];
    onCancel: (requestId: string) => void | Promise<void>;
};

function requestStatus(request: AccessRequest, t: TFunction) {
    if (request.decisionStatus === "PENDING") {
        return t("accessRequestsPending");
    }

    if (request.decisionStatus === "APPROVED" && request.provisioningStatus === "SUCCEEDED") {
        return t("accessRequestsGranted", { date: request.decision?.decidedAt ?? request.requestedAt });
    }

    const statusKeys: Record<string, string> = {
        APPROVED: "accessRequestsApproved",
        CANCELED: "accessRequestsCanceled",
        REJECTED: "accessRequestsRejected"
    };
    return t(statusKeys[request.decisionStatus] ?? request.decisionStatus);
}

export function MyRequestsPage({ requests, onCancel }: MyRequestsPageProps) {
    const { t } = useTranslation();
    const [selectedRequest, setSelectedRequest] = useState<AccessRequest>();

    return (
        <section aria-labelledby="my-requests-title">
            <h1 id="my-requests-title">{t("accessRequestsMyRequests")}</h1>
            {requests.map((request) => (
                <article key={request.id} aria-label={request.entitlementName}>
                    <h2>{request.entitlementName}</h2>
                    <p>{request.resourceType}</p>
                    <p>{requestStatus(request, t)}</p>
                    {request.decisionStatus === "PENDING" && (
                        <button type="button" onClick={() => void onCancel(request.id)}>
                            {t("accessRequestsCancelRequest")}
                        </button>
                    )}
                    <button type="button" onClick={() => setSelectedRequest(request)}>
                        {t("accessRequestsViewDetails")}
                    </button>
                </article>
            ))}
            {selectedRequest && (
                <div
                    aria-label={t("accessRequestsRequestDetails", { entitlement: selectedRequest.entitlementName })}
                    aria-modal="true"
                    role="dialog"
                >
                    <h2>{selectedRequest.entitlementName}</h2>
                    <p>{selectedRequest.justification}</p>
                    {selectedRequest.decision && (
                        <>
                            <p>{selectedRequest.decision.approver}</p>
                            <p>{selectedRequest.decision.comment}</p>
                        </>
                    )}
                    <dl>
                        <div>
                            <dt>{t("accessRequestsDecision")}</dt>
                            <dd>{selectedRequest.decisionStatus}</dd>
                        </div>
                        <div>
                            <dt>{t("accessRequestsProvisioning")}</dt>
                            <dd>{selectedRequest.provisioningStatus}</dd>
                        </div>
                    </dl>
                    <h3>{t("accessRequestsHistory")}</h3>
                    <ol>
                        {selectedRequest.history.map((event) => (
                            <li key={`${event.type}-${event.occurredAt}`}>
                                <strong>{event.type}</strong> <time>{event.occurredAt}</time>
                            </li>
                        ))}
                    </ol>
                    <button type="button" onClick={() => setSelectedRequest(undefined)}>
                        {t("accessRequestsClose")}
                    </button>
                </div>
            )}
        </section>
    );
}
