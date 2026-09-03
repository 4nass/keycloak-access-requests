import { Alert, AlertActionLink, Spinner } from "@patternfly/react-core";
import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

import { presentAccessRequestsError, type Page } from "../api/AccessRequestsApi";
import type { AccessRequestPaginationState } from "./AccessRequestPagination";

type LoadState<T> = {
    error?: unknown;
    loading: boolean;
    refreshError?: unknown;
    value?: T;
};

export type PageParameters = {
    page: number;
    search: string;
    size: number;
};

export function usePagedLoader<T>(load: (parameters: PageParameters) => Promise<Page<T>>) {
    const [parameters, setParameters] = useState<PageParameters>({ page: 0, search: "", size: 20 });
    const loadCurrentPage = useCallback(() => load(parameters), [load, parameters]);
    const setPage = useCallback((page: number) => {
        setParameters((current) => current.page === page ? current : { ...current, page });
    }, []);
    const setPageSize = useCallback((size: number) => {
        setParameters((current) => current.size === size ? current : { ...current, page: 0, size });
    }, []);
    const setSearch = useCallback((search: string) => {
        const normalizedSearch = search.trim();
        setParameters((current) => current.search === normalizedSearch
            ? current
            : { ...current, page: 0, search: normalizedSearch });
    }, []);

    return { ...useLoader(loadCurrentPage), setPage, setPageSize, setSearch };
}

export function useDebouncedValue(value: string, delay = 300) {
    const [debouncedValue, setDebouncedValue] = useState(value);

    useEffect(() => {
        const timeout = window.setTimeout(() => setDebouncedValue(value), delay);
        return () => window.clearTimeout(timeout);
    }, [delay, value]);

    return debouncedValue;
}

export function pagination<T>(
    page: Page<T>,
    setPage: (page: number) => void,
    setPageSize: (size: number) => void
): AccessRequestPaginationState {
    return {
        onPageChange: setPage,
        onPageSizeChange: setPageSize,
        page: page.page,
        size: page.size,
        total: page.total
    };
}

export function LoadingState() {
    const { t } = useTranslation();
    return <Spinner aria-label={t("accessRequestsLoading")} />;
}

export function LoadError({ error, onRetry }: { error: unknown; onRetry: () => Promise<void> }) {
    const { t } = useTranslation();
    const presentation = presentAccessRequestsError(error);

    return (
        <Alert
            actionLinks={
                <AlertActionLink onClick={() => void onRetry()}>
                    {t("accessRequestsRetry")}
                </AlertActionLink>
            }
            isInline
            role="alert"
            title={t("accessRequestsLoadError")}
            variant="danger"
        >
            <p>{t(presentation.messageKey)}</p>
            {presentation.requestId && <p>{t("accessRequestsErrorReference", { requestId: presentation.requestId })}</p>}
        </Alert>
    );
}

export function RefreshError({ error, onRetry }: { error: unknown; onRetry: () => Promise<void> }) {
    return <LoadError error={error} onRetry={onRetry} />;
}

function useLoader<T>(load: () => Promise<T>) {
    const [state, setState] = useState<LoadState<T>>({ loading: true });
    const requestVersion = useRef(0);
    const reload = useCallback(async () => {
        const version = ++requestVersion.current;
        setState((current) => current.value
            ? { ...current, refreshError: undefined }
            : { error: undefined, loading: true });
        try {
            const value = await load();
            if (version === requestVersion.current) {
                setState({ loading: false, value });
            }
        } catch (error) {
            if (version === requestVersion.current) {
                setState((current) => current.value
                    ? { ...current, refreshError: error }
                    : { error, loading: false });
            }
        }
    }, [load]);

    useEffect(() => {
        void reload();
    }, [reload]);

    return { ...state, reload };
}
