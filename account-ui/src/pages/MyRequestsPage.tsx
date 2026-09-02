import { useState } from "react";

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

function requestStatus(request: AccessRequest) {
    if (request.decisionStatus === "PENDING") {
        return "Pending";
    }

    if (request.decisionStatus === "APPROVED" && request.provisioningStatus === "SUCCEEDED") {
        return `Granted ${request.decision?.decidedAt ?? request.requestedAt}`;
    }

    return request.decisionStatus;
}

export function MyRequestsPage({ requests, onCancel }: MyRequestsPageProps) {
    const [selectedRequest, setSelectedRequest] = useState<AccessRequest>();

    return (
        <section aria-labelledby="my-requests-title">
            <h1 id="my-requests-title">My Requests</h1>
            {requests.map((request) => (
                <article key={request.id} aria-label={request.entitlementName}>
                    <h2>{request.entitlementName}</h2>
                    <p>{request.resourceType}</p>
                    <p>{requestStatus(request)}</p>
                    {request.decisionStatus === "PENDING" && (
                        <button type="button" onClick={() => void onCancel(request.id)}>
                            Cancel request
                        </button>
                    )}
                    <button type="button" onClick={() => setSelectedRequest(request)}>
                        View details
                    </button>
                </article>
            ))}
            {selectedRequest && (
                <div
                    aria-label={`${selectedRequest.entitlementName} request details`}
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
                            <dt>Decision</dt>
                            <dd>{selectedRequest.decisionStatus}</dd>
                        </div>
                        <div>
                            <dt>Provisioning</dt>
                            <dd>{selectedRequest.provisioningStatus}</dd>
                        </div>
                    </dl>
                    <h3>History</h3>
                    <ol>
                        {selectedRequest.history.map((event) => (
                            <li key={`${event.type}-${event.occurredAt}`}>
                                <strong>{event.type}</strong> <time>{event.occurredAt}</time>
                            </li>
                        ))}
                    </ol>
                    <button type="button" onClick={() => setSelectedRequest(undefined)}>
                        Close
                    </button>
                </div>
            )}
        </section>
    );
}
