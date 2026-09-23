import {
    routes as keycloakRoutes,
    useAccess,
    useEnvironment,
    useRealm,
    useServerInfo,
    type AdminEnvironment
} from "@keycloak/keycloak-admin-ui";
import {
    Label,
    Nav,
    NavGroup,
    PageSidebar,
    PageSidebarBody
} from "@patternfly/react-core";
import { useEffect, useState, type FormEvent } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { useEntitlementsAdminApi } from "./api/useEntitlementsAdminApi";

type AccessType = Parameters<ReturnType<typeof useAccess>["hasAccess"]>[number];

type SelectedItem = {
    to: string;
};

/**
 * Forked from Keycloak 26.7.3's PageNav:
 * https://github.com/keycloak/keycloak/blob/26.7.3/js/apps/admin-ui/src/PageNav.tsx
 *
 * The public PageNav export has no supported contribution point for an additional navigation item.
 * Keep this implementation aligned with that source on every Keycloak minor upgrade; the only
 * product-specific addition is the server-authorized Access requests catalog entry.
 */
export function AccessRequestsAdminPageNav() {
    const { t } = useTranslation();
    const { environment } = useEnvironment<AdminEnvironment>();
    const { hasAccess, hasSomeAccess } = useAccess();
    const { features } = useServerInfo();
    const { realm, realmRepresentation } = useRealm();
    const api = useEntitlementsAdminApi();
    const navigate = useNavigate();
    const [capabilities, setCapabilities] = useState({
        canManageCatalog: false,
        canManageNotifications: false,
        canManageProvisioningFailures: false
    });

    useEffect(() => {
        let active = true;

        void api.capabilities()
            .then((nextCapabilities) => {
                if (active) {
                    setCapabilities(nextCapabilities);
                }
            })
            .catch(() => {
                if (active) {
                    setCapabilities({
                        canManageCatalog: false,
                        canManageNotifications: false,
                        canManageProvisioningFailures: false
                    });
                }
            });

        return () => {
            active = false;
        };
    }, [api]);

    const isFeatureEnabled = (feature: string) => features?.some(({ enabled, name }) => enabled && name === feature) ?? false;
    const showManage = hasSomeAccess(
        "view-realm",
        "query-groups",
        "query-users",
        "query-clients",
        "query-organizations",
        "view-events"
    );
    const showConfigure = hasSomeAccess("view-realm", "query-clients", "view-identity-providers");
    const showOrganizations = isFeatureEnabled("ORGANIZATION") && hasAccess(({ hasAny }) => hasAny("manage-realm", "query-organizations"));
    const showWorkflows = hasSomeAccess("realm-admin", "admin") && isFeatureEnabled("WORKFLOWS");
    const showManageRealm = environment.masterRealm === environment.realm;
    const canManageRealms = hasKeycloakRouteAccess("/realms", hasAccess);

    const onSelect = (_event: FormEvent<HTMLInputElement>, item: SelectedItem) => {
        void navigate(item.to);
        _event.preventDefault();
    };

    return (
        <PageSidebar className="keycloak__page_nav__nav">
            <PageSidebarBody>
                <Nav onSelect={onSelect}>
                    <h2 className="pf-v5-c-nav__section-title" style={{ wordWrap: "break-word" }}>
                        <span data-testid="currentRealm">{realmRepresentation.displayName || realm}</span>{" "}
                        <Label color="blue">{t("currentRealm")}</Label>
                    </h2>
                    {showManageRealm && canManageRealms && (
                        <NavGroup>
                            <KeycloakNavItem path="/realms" title={t("manageRealms")} />
                        </NavGroup>
                    )}
                    {showManage && (
                        <NavGroup aria-label={t("manage")} title={t("manage")}>
                            {showOrganizations && realmRepresentation.organizationsEnabled && (
                                <KeycloakNavItem path="/organizations" title={t("organizations")} />
                            )}
                            <KeycloakNavItem path="/clients" title={t("clients")} />
                            <KeycloakNavItem path="/client-scopes" title={t("clientScopes")} />
                            <KeycloakNavItem path="/roles" title={t("realmRoles")} />
                            <KeycloakNavItem path="/users" title={t("users")} />
                            <KeycloakNavItem path="/groups" title={t("groups")} />
                            <KeycloakNavItem path="/sessions" title={t("sessions")} />
                            <KeycloakNavItem path="/events" title={t("events")} />
                        </NavGroup>
                    )}
                    {(showConfigure || capabilities.canManageCatalog || capabilities.canManageNotifications
                        || capabilities.canManageProvisioningFailures) && (
                        <NavGroup aria-label={t("configure")} title={t("configure")}>
                            {showConfigure && (
                                <>
                                    <KeycloakNavItem path="/realm-settings" title={t("realmSettings")} />
                                    <KeycloakNavItem path="/authentication" title={t("authentication")} />
                                    {isFeatureEnabled("ADMIN_FINE_GRAINED_AUTHZ_V2") && realmRepresentation.adminPermissionsEnabled && (
                                        <KeycloakNavItem path="/permissions" title={t("permissions")} />
                                    )}
                                    <KeycloakNavItem path="/identity-providers" title={t("identityProviders")} />
                                    <KeycloakNavItem path="/user-federation" title={t("userFederation")} />
                                    {showWorkflows && <KeycloakNavItem path="/workflows" title={t("workflows")} />}
                                </>
                            )}
                            {capabilities.canManageCatalog && <AccessRequestsNavItem />}
                            {capabilities.canManageNotifications && <NotificationDeliveriesNavItem />}
                            {capabilities.canManageProvisioningFailures && <FailedProvisioningNavItem />}
                        </NavGroup>
                    )}
                </Nav>
            </PageSidebarBody>
        </PageSidebar>
    );
}

function KeycloakNavItem({ path, title }: { path: string; title: string }) {
    const { hasAccess } = useAccess();
    const { realm } = useRealm();

    if (!hasKeycloakRouteAccess(path, hasAccess)) {
        return null;
    }

    return <NavigationItem path={path} realm={realm} title={title} />;
}

function hasKeycloakRouteAccess(path: string, hasAccess: (...access: AccessType[]) => boolean) {
    const route = keycloakRoutes.find((candidate) => candidate.path.replace(/\/:.+?(\?|(?:(?!\/).)*|$)/g, "") === path);
    const access = route?.handle?.access as AccessType | AccessType[] | undefined;

    return access !== undefined && (Array.isArray(access) ? hasAccess(...access) : hasAccess(access));
}

function AccessRequestsNavItem() {
    const { t } = useTranslation();
    const { realm } = useRealm();

    return <NavigationItem path="/access-requests" realm={realm} title={t("accessRequestsAdminCatalog")} />;
}

function NotificationDeliveriesNavItem() {
    const { t } = useTranslation();
    const { realm } = useRealm();

    return <NavigationItem
        path="/access-requests/notification-deliveries"
        realm={realm}
        title={t("accessRequestsAdminNotificationDelivery")}
    />;
}

function FailedProvisioningNavItem() {
    const { t } = useTranslation();
    const { realm } = useRealm();

    return <NavigationItem
        path="/access-requests/provisioning-failures"
        realm={realm}
        title={t("accessRequestsAdminFailedProvisioning")}
    />;
}

function NavigationItem({ path, realm, title }: { path: string; realm: string; title: string }) {
    const target = `/${encodeURIComponent(realm)}${path}`;
    const id = `nav-item${path.replace("/", "-")}`;

    return (
        <li>
            <NavLink
                className={({ isActive }) => `pf-v5-c-nav__link${isActive ? " pf-m-current" : ""}`}
                data-testid={id}
                id={id}
                to={target}
            >
                {title}
            </NavLink>
        </li>
    );
}
