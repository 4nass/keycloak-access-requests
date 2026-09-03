import { Nav, NavItem, NavList, PageSidebar, PageSidebarBody } from "@patternfly/react-core";
import { useTranslation } from "react-i18next";
import { Link, useMatch } from "react-router-dom";

import { AccessRequestNavigation } from "./pages/AccessRequestNavigation";

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
                <AccessRequestNavigation canApprove={false} />
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
