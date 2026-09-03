import { Spinner } from "@patternfly/react-core";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    type AccessRequestsApi,
    type CatalogItem,
    type Page,
    type PendingRequest,
    type RequestDetails,
    type RequestSummary
} from "../api/AccessRequestsApi";
import { useAccessRequestsApi } from "../api/useAccessRequestsApi";
import { ApprovalsPage, type PendingApproval } from "./ApprovalsPage";
import { MyRequestsPage, type AccessRequest } from "./MyRequestsPage";
import { RequestAccessPage, type RequestableEntitlement } from "./RequestAccessPage";

type LoadState<T> = {
    error?: unknown;
    loading: boolean;
    value?: T;
};

export function RequestAccessRoutePage() {
    const api = useAccessRequestsApi();
    const loadCatalog = useCallback(() => api.catalog(), [api]);
    const { error, loading, reload, value } = useLoader(loadCatalog);

    if (error) {
        return <LoadError error={error} onRetry={reload} />;
    }
    if (loading || !value) {
        return <LoadingState />;
    }

    return (
        <RequestAccessPage
            entries={catalogEntries(value)}
            onRefresh={reload}
            onRequest={async (submission) => {
                await api.submitRequest(submission);
            }}
        />
    );
}

export function MyRequestsRoutePage() {
    const api = useAccessRequestsApi();
    const loadRequests = useCallback(() => api.mine(), [api]);
    const { error, loading, reload, value } = useLoader(loadRequests);

    if (error) {
        return <LoadError error={error} onRetry={reload} />;
    }
    if (loading || !value) {
        return <LoadingState />;
    }

    return (
        <MyRequestsPage
            onCancel={api.cancel}
            onRefresh={reload}
            onRequestDetails={async (requestId) => requestDetails(await api.requestDetails(requestId))}
            requests={requestEntries(value)}
        />
    );
}

export function ApprovalsRoutePage() {
    const api = useAccessRequestsApi();
    const loadPendingRequests = useCallback(() => api.pending(), [api]);
    const { error, loading, reload, value } = useLoader(loadPendingRequests);

    if (error) {
        return <LoadError error={error} onRetry={reload} />;
    }
    if (loading || !value) {
        return <LoadingState />;
    }

    return <ApprovalsPage onApprove={approve(api)} onRefresh={reload} onReject={reject(api)} requests={pendingEntries(value)} />;
}

function useLoader<T>(load: () => Promise<T>) {
    const [state, setState] = useState<LoadState<T>>({ loading: true });
    const reload = useCallback(async () => {
        setState((current) => ({ ...current, error: undefined, loading: true }));
        try {
            const value = await load();
            setState({ loading: false, value });
        } catch (error) {
            setState({ error, loading: false });
        }
    }, [load]);

    useEffect(() => {
        void reload();
    }, [reload]);

    return { ...state, reload };
}

function LoadingState() {
    const { t } = useTranslation();
    return <Spinner aria-label={t("accessRequestsLoading")} />;
}

function LoadError({ error, onRetry }: { error: unknown; onRetry: () => Promise<void> }) {
    const { t } = useTranslation();
    const message = error instanceof Error ? error.message : String(error);

    return (
        <section role="alert">
            <p>{t("accessRequestsLoadError")}</p>
            <p>{message}</p>
            <button type="button" onClick={() => void onRetry()}>{t("accessRequestsRetry")}</button>
        </section>
    );
}

function catalogEntries(page: Page<CatalogItem>): RequestableEntitlement[] {
    return page.items.map((item) => ({
        alreadyGranted: item.alreadyGranted,
        description: item.description,
        id: item.id,
        name: item.displayName,
        pendingRequest: item.pendingRequest,
        resourceType: item.resourceType,
        riskLevel: item.riskLevel
    }));
}

function requestEntries(page: Page<RequestSummary>): AccessRequest[] {
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

function pendingEntries(page: Page<PendingRequest>): PendingApproval[] {
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
