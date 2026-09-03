import { Pagination, Toolbar, ToolbarContent, ToolbarItem } from "@patternfly/react-core";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";

export type AccessRequestPaginationState = {
    page: number;
    size: number;
    total: number;
    onPageChange: (page: number) => void;
    onPageSizeChange: (size: number) => void;
};

type AccessRequestPaginationProps = {
    children?: ReactNode;
    pagination?: AccessRequestPaginationState;
};

const perPageOptions = [10, 20, 50].map((value) => ({ title: String(value), value }));

export function AccessRequestPagination({ children, pagination }: AccessRequestPaginationProps) {
    const { t } = useTranslation();
    if (!pagination && !children) {
        return null;
    }

    return (
        <Toolbar aria-label={t("accessRequestsPagination")}>
            <ToolbarContent>
                {children && <ToolbarItem variant="search-filter">{children}</ToolbarItem>}
                {pagination && pagination.total > 0 && (
                    <ToolbarItem align={{ default: "alignRight" }} variant="pagination">
                        <Pagination
                            itemCount={pagination.total}
                            onPerPageSelect={(_, size) => pagination.onPageSizeChange(size)}
                            onSetPage={(_, page) => pagination.onPageChange(page - 1)}
                            page={pagination.page + 1}
                            perPage={pagination.size}
                            perPageOptions={perPageOptions}
                            titles={{
                                currPageAriaLabel: t("accessRequestsCurrentPage"),
                                items: t("accessRequestsItems"),
                                itemsPerPage: t("accessRequestsItemsPerPage"),
                                ofWord: t("accessRequestsOf"),
                                optionsToggleAriaLabel: t("accessRequestsItemsPerPage"),
                                page: t("accessRequestsPageLabel"),
                                pages: t("accessRequestsPages"),
                                paginationAriaLabel: t("accessRequestsPagination"),
                                perPageSuffix: t("accessRequestsPerPage"),
                                toFirstPageAriaLabel: t("accessRequestsFirstPage"),
                                toLastPageAriaLabel: t("accessRequestsLastPage"),
                                toNextPageAriaLabel: t("accessRequestsNextPage"),
                                toPreviousPageAriaLabel: t("accessRequestsPreviousPage")
                            }}
                            variant="top"
                            widgetId="access-request-pagination"
                        />
                    </ToolbarItem>
                )}
            </ToolbarContent>
        </Toolbar>
    );
}
