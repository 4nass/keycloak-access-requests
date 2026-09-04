import { useCallback } from "react";

import type { RequestDetails, RequestSummary } from "../api/AccessRequestsApi";
import { useAccessRequestsApi } from "../api/useAccessRequestsApi";
import { MyRequestsPage, type AccessRequest } from "./MyRequestsPage";
import {
    LoadError,
    LoadingState,
    pagination,
    RefreshError,
    type PageParameters,
    usePagedLoader
} from "./AccessRequestRoutePageSupport";

export function MyRequestsRoutePage() {
    const api = useAccessRequestsApi();
    const loadRequests = useCallback(({ page, size }: PageParameters) => api.mine({ page, size }), [api]);
    const { error, loading, refreshError, reload, setPage, setPageSize, value } = usePagedLoader(loadRequests);

    if (error) {
        return <LoadError error={error} onRetry={reload} />;
    }
    if (loading || !value) {
        return <LoadingState />;
    }

    return <>
        {refreshError && <RefreshError error={refreshError} onRetry={reload} />}
        <MyRequestsPage
            onCancel={api.cancel}
            onRefresh={reload}
            onRequestDetails={async (requestId) => requestDetails(await api.requestDetails(requestId))}
            pagination={pagination(value, setPage, setPageSize)}
            requests={requestEntries(value)}
        />
    </>;
}

function requestEntries(page: { items: RequestSummary[] }): AccessRequest[] {
    return page.items.map((item) => ({
        decisionStatus: item.decisionStatus,
        entitlementName: item.resourceName,
        history: [],
        id: item.id,
        justification: "",
        provisioningStatus: item.provisioningStatus,
        requestedAt: item.createdAt,
        resourceType: item.resourceType
    }));
}

function requestDetails(details: RequestDetails) {
    return {
        decision: details.decision
            ? {
                    approver: details.decision.approverId,
                    comment: details.decision.comment ?? "",
                    decidedAt: details.decision.decidedAt
                }
            : undefined,
        history: details.history,
        justification: details.justification
    };
}

export default MyRequestsRoutePage;
