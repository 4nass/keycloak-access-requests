import { useRef, useState } from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";

import { AccessibleDialog } from "./AccessibleDialog";

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
    onRefresh?: () => void | Promise<void>;
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

function errorMessage(error: unknown) {
    return error instanceof Error ? error.message : String(error);
}

export function MyRequestsPage({ requests, onCancel, onRefresh }: MyRequestsPageProps) {
    const { t } = useTranslation();
    const [selectedRequest, setSelectedRequest] = useState<AccessRequest>();
    const [cancellingRequestId, setCancellingRequestId] = useState<string>();
    const [cancellationError, setCancellationError] = useState<string>();
    const closeButtonRef = useRef<HTMLButtonElement>(null);

    const cancel = async (requestId: string) => {
        if (cancellingRequestId) {
            return;
        }

        setCancellingRequestId(requestId);
        setCancellationError(undefined);

        try {
            await onCancel(requestId);
            await onRefresh?.();
        } catch (error) {
            setCancellationError(errorMessage(error));
        } finally {
            setCancellingRequestId(undefined);
        }
    };

    return (
        <section aria-labelledby="my-requests-title">
            <h1 id="my-requests-title">{t("accessRequestsMyRequests")}</h1>
            {cancellationError && <p role="alert">{cancellationError}</p>}
            {requests.map((request) => (
                <article key={request.id} aria-label={request.entitlementName}>
                    <h2>{request.entitlementName}</h2>
                    <p>{request.resourceType}</p>
                    <p>{requestStatus(request, t)}</p>
                    {request.decisionStatus === "PENDING" && (
                        <button
                            type="button"
                            disabled={cancellingRequestId === request.id}
                            onClick={() => void cancel(request.id)}
                        >
                            {t("accessRequestsCancelRequest")}
                        </button>
                    )}
                    <button type="button" onClick={() => setSelectedRequest(request)}>
                        {t("accessRequestsViewDetails")}
                    </button>
                </article>
            ))}
            {selectedRequest && (
                <AccessibleDialog
                    ariaLabel={t("accessRequestsRequestDetails", { entitlement: selectedRequest.entitlementName })}
                    initialFocusRef={closeButtonRef}
                    onClose={() => setSelectedRequest(undefined)}
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
                    <button ref={closeButtonRef} type="button" onClick={() => setSelectedRequest(undefined)}>
                        {t("accessRequestsClose")}
                    </button>
                </AccessibleDialog>
            )}
        </section>
    );
}
