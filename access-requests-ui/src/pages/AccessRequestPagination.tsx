import { useTranslation } from "react-i18next";

export type AccessRequestPaginationState = {
    page: number;
    size: number;
    total: number;
    onPageChange: (page: number) => void;
};

type AccessRequestPaginationProps = {
    pagination?: AccessRequestPaginationState;
};

export function AccessRequestPagination({ pagination }: AccessRequestPaginationProps) {
    const { t } = useTranslation();
    if (!pagination || pagination.total <= pagination.size) {
        return null;
    }

    const pageCount = Math.ceil(pagination.total / pagination.size);

    return (
        <nav aria-label={t("accessRequestsPagination")}>
            <p aria-live="polite">{t("accessRequestsPage", { page: pagination.page + 1, pages: pageCount })}</p>
            <button
                type="button"
                disabled={pagination.page === 0}
                onClick={() => pagination.onPageChange(pagination.page - 1)}
            >
                {t("accessRequestsPreviousPage")}
            </button>
            <button
                type="button"
                disabled={pagination.page + 1 >= pageCount}
                onClick={() => pagination.onPageChange(pagination.page + 1)}
            >
                {t("accessRequestsNextPage")}
            </button>
        </nav>
    );
}
