import { type AccountEnvironment, useEnvironment } from "@keycloak/keycloak-account-ui";
import { Nav, NavItem, NavList, PageSidebar, PageSidebarBody } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useMatch } from "react-router-dom";

import { AccessRequestNavigation } from "./pages/AccessRequestNavigation";
import { useAccessRequestsApi } from "./api/useAccessRequestsApi";

const navigationItems: { path: string; label: string; feature?: keyof AccountEnvironment["features"] }[] = [
    { path: "personal-info", label: "personalInfo" },
    { path: "account-security/device-activity", label: "deviceActivity" },
    { path: "account-security/linked-accounts", label: "linkedAccounts", feature: "isLinkedAccountsEnabled" },
    { path: "account-security/signing-in", label: "signingIn" },
    { path: "applications", label: "applications", feature: "isViewApplicationsEnabled" },
    { path: "groups", label: "groups", feature: "isViewGroupsEnabled" },
    { path: "resources", label: "resources", feature: "isMyResourcesEnabled" }
];

export function PageNav() {
    const { t } = useTranslation();
    const { environment } = useEnvironment<AccountEnvironment>();
    const api = useAccessRequestsApi();
    const [canApprove, setCanApprove] = useState(false);

    useEffect(() => {
        let active = true;
        void api.capabilities()
            .then((capabilities) => {
                if (active) {
                    setCanApprove(capabilities.canApprove);
                }
            })
            .catch(() => {
                if (active) {
                    setCanApprove(false);
                }
            });
        return () => {
            active = false;
        };
    }, [api]);

    return (
        <PageSidebar>
            <PageSidebarBody>
                <Nav aria-label={t("accountManagement")}>
                    <NavList>
                        {navigationItems.filter(({ feature }) => !feature || environment.features[feature])
                            .map(({ path, label }) => (
                                <NavigationItem key={path} path={path} label={t(label)} />
                            ))}
                        <AccessRequestNavigation canApprove={canApprove} />
                    </NavList>
                </Nav>
            </PageSidebarBody>
        </PageSidebar>
    );
}

function NavigationItem({ path, label }: { path: string; label: string }) {
    const match = useMatch(path);

    return (
        <NavItem to={path} isActive={match !== null} component={(props) => <Link {...props} to={path} />}>
            {label}
        </NavItem>
    );
}
