import { useCallback, useEffect, useState } from "react";

import type { CatalogItem } from "../api/AccessRequestsApi";
import { useAccessRequestsApi } from "../api/useAccessRequestsApi";
import { RequestAccessPage, type RequestableEntitlement } from "./RequestAccessPage";
import {
    LoadError,
    LoadingState,
    pagination,
    RefreshError,
    type PageParameters,
    useDebouncedValue,
    usePagedLoader
} from "./AccessRequestRoutePageSupport";

export function RequestAccessRoutePage() {
    const api = useAccessRequestsApi();
    const [search, setSearch] = useState("");
    const debouncedSearch = useDebouncedValue(search);
    const loadCatalog = useCallback(
        ({ page, search, size }: PageParameters) => api.catalog({ page, search: search || undefined, size }),
        [api]
    );
    const { error, loading, refreshError, reload, setPage, setPageSize, setSearch: setCatalogSearch, value } = usePagedLoader(loadCatalog);

    useEffect(() => {
        setCatalogSearch(debouncedSearch);
    }, [debouncedSearch, setCatalogSearch]);

    if (error) {
        return <LoadError error={error} onRetry={reload} />;
    }
    if (loading || !value) {
        return <LoadingState />;
    }

    return <>
        {refreshError && <RefreshError error={refreshError} onRetry={reload} />}
        <RequestAccessPage
            entries={catalogEntries(value)}
            onRefresh={reload}
            onRequest={async (submission) => {
                await api.submitRequest(submission);
            }}
            pagination={pagination(value, setPage, setPageSize)}
            search={{ onChange: setSearch, value: search }}
        />
    </>;
}

function catalogEntries(page: { items: CatalogItem[] }): RequestableEntitlement[] {
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

export default RequestAccessRoutePage;
