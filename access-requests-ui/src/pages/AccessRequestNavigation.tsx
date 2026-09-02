import { useTranslation } from "react-i18next";

type AccessRequestNavigationProps = {
    canApprove: boolean;
};

export function AccessRequestNavigation({ canApprove }: AccessRequestNavigationProps) {
    const { t } = useTranslation();

    return (
        <nav aria-label={t("accessRequestsNav")}>
            <ul>
                <li>
                    <a href="request-access">{t("accessRequestsRequestAccess")}</a>
                </li>
                <li>
                    <a href="my-requests">{t("accessRequestsMyRequests")}</a>
                </li>
                {canApprove && (
                    <li>
                        <a href="approvals">{t("accessRequestsApprovals")}</a>
                    </li>
                )}
            </ul>
        </nav>
    );
}
