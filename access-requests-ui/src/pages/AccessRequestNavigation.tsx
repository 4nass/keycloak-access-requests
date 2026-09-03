import { NavExpandable, NavItem } from "@patternfly/react-core";
import { useTranslation } from "react-i18next";
import { Link, useMatch } from "react-router-dom";

type AccessRequestNavigationProps = {
    canApprove: boolean;
};

export function AccessRequestNavigation({ canApprove }: AccessRequestNavigationProps) {
    const { t } = useTranslation();
    const requestAccessMatch = useMatch("request-access");
    const myRequestsMatch = useMatch("my-requests");
    const approvalsMatch = useMatch("approvals");
    const isActive = requestAccessMatch !== null || myRequestsMatch !== null || approvalsMatch !== null;

    return (
        <NavExpandable isActive={isActive} isExpanded={isActive} title={t("accessRequestsNav")}>
            <AccessRequestNavigationItem
                isActive={requestAccessMatch !== null}
                label={t("accessRequestsRequestAccess")}
                path="request-access"
            />
            <AccessRequestNavigationItem
                isActive={myRequestsMatch !== null}
                label={t("accessRequestsMyRequests")}
                path="my-requests"
            />
            {canApprove && (
                <AccessRequestNavigationItem
                    isActive={approvalsMatch !== null}
                    label={t("accessRequestsApprovals")}
                    path="approvals"
                />
            )}
        </NavExpandable>
    );
}

type AccessRequestNavigationItemProps = {
    isActive: boolean;
    label: string;
    path: string;
};

function AccessRequestNavigationItem({ isActive, label, path }: AccessRequestNavigationItemProps) {
    return (
        <NavItem to={path} isActive={isActive} component={(props) => <Link {...props} to={path} />}>
            {label}
        </NavItem>
    );
}
