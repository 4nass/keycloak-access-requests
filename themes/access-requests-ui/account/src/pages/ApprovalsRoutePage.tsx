import { useCallback } from "react";

import type { AccessRequestsApi, PendingRequest } from "../api/AccessRequestsApi";
import { useAccessRequestsApi } from "../api/useAccessRequestsApi";
import { ApprovalsPage, type PendingApproval } from "./ApprovalsPage";
import {
    LoadError,
    LoadingState,
    pagination,
    RefreshError,
    type PageParameters,
    usePagedLoader
} from "./AccessRequestRoutePageSupport";

export function ApprovalsRoutePage() {
    const api = useAccessRequestsApi();
    const loadPendingRequests = useCallback(({ page, size }: PageParameters) => api.pending({ page, size }), [api]);
    const { error, loading, refreshError, reload, setPage, setPageSize, value } = usePagedLoader(loadPendingRequests);

    if (error) {
        return <LoadError error={error} onRetry={reload} />;
    }
    if (loading || !value) {
        return <LoadingState />;
    }

    return <>
        {refreshError && <RefreshError error={refreshError} onRetry={reload} />}
        <ApprovalsPage
            onApprove={approve(api)}
            onRefresh={reload}
            onReject={reject(api)}
            pagination={pagination(value, setPage, setPageSize)}
            requests={pendingEntries(value)}
        />
    </>;
}

function pendingEntries(page: { items: PendingRequest[] }): PendingApproval[] {
    return page.items.map((item) => ({
        entitlementName: item.resourceName,
        id: item.id,
        justification: item.justification,
        requestedAt: item.createdAt,
        requester: item.requesterId,
        resourceType: item.resourceType,
        riskLevel: item.riskLevel
    }));
}

function approve(api: AccessRequestsApi) {
    return ({ requestId, comment }: { requestId: string; comment: string }) => api.approve(requestId, { comment });
}

function reject(api: AccessRequestsApi) {
    return ({ requestId, comment }: { requestId: string; comment: string }) => api.reject(requestId, { comment });
}

export default ApprovalsRoutePage;
