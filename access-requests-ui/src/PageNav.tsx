import { Nav, NavItem, NavList, PageSidebar, PageSidebarBody } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useMatch } from "react-router-dom";

import { AccessRequestNavigation } from "./pages/AccessRequestNavigation";
import { useAccessRequestsApi } from "./api/useAccessRequestsApi";

const navigationItems = [
    { path: "personal-info", label: "personalInfo" },
    { path: "account-security/device-activity", label: "deviceActivity" },
    { path: "account-security/linked-accounts", label: "linkedAccounts" },
    { path: "account-security/signing-in", label: "signingIn" },
    { path: "applications", label: "applications" },
    { path: "groups", label: "groups" },
    { path: "resources", label: "resources" }
];

export function PageNav() {
    const { t } = useTranslation();
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
                        {navigationItems.map(({ path, label }) => (
                            <NavigationItem key={path} path={path} label={t(label)} />
                        ))}
                    </NavList>
                </Nav>
                <AccessRequestNavigation canApprove={canApprove} />
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
