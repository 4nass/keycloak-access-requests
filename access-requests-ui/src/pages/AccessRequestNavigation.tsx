import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";

type AccessRequestNavigationProps = {
    canApprove: boolean;
};

export function AccessRequestNavigation({ canApprove }: AccessRequestNavigationProps) {
    const { t } = useTranslation();

    return (
        <nav aria-label={t("accessRequestsNav")}>
            <ul>
                <li>
                    <Link to="request-access">{t("accessRequestsRequestAccess")}</Link>
                </li>
                <li>
                    <Link to="my-requests">{t("accessRequestsMyRequests")}</Link>
                </li>
                {canApprove && (
                    <li>
                        <Link to="approvals">{t("accessRequestsApprovals")}</Link>
                    </li>
                )}
            </ul>
        </nav>
    );
}
