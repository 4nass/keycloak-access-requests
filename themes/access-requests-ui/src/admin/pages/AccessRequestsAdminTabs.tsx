import { Tab, Tabs, TabTitleText } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useInRouterContext, useLocation, useNavigate } from "react-router-dom";

import type { AdminCapabilities } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";

export type AdminTab = "catalog" | "assurance" | "events" | "incidents"
    | "notifications" | "provisioning" | "revocations";

export function AccessRequestsAdminTabs({ active }: { active: AdminTab }) {
    // The catalog is also rendered in isolated component tests without a router.
    return useInRouterContext() ? <RoutedTabs active={active} /> : null;
}

function RoutedTabs({ active }: { active: AdminTab }) {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const { pathname } = useLocation();
    const navigate = useNavigate();
    const [capabilities, setCapabilities] = useState<AdminCapabilities>();

    useEffect(() => {
        let activeRequest = true;
        void api.capabilities().then((allowed) => {
            if (activeRequest) setCapabilities(allowed);
        }).catch(() => {
            if (activeRequest) setCapabilities(undefined);
        });
        return () => { activeRequest = false; };
    }, [api]);

    // Match the route segment after the realm, not a substring inside its name.
    const base = /^\/[^/]+\/access-requests(?=\/|$)/.exec(pathname)?.[0] ?? pathname;
    const paths = {
        catalog: base,
        assurance: `${base}/assurance-policy`,
        events: `${base}/events`,
        incidents: `${base}/incidents`
    };
    const selectedTab = active === "notifications" || active === "provisioning" || active === "revocations"
        ? "incidents" : active;

    if (!capabilities || !Object.values(capabilities).some(Boolean)) {
        const activeTitle = {
            catalog: "accessRequestsAdminCatalogTab",
            assurance: "accessRequestsAdminAssurancePolicy",
            events: "accessRequestsAdminEvents",
            incidents: "accessRequestsAdminIncidents",
            notifications: "accessRequestsAdminIncidents",
            provisioning: "accessRequestsAdminIncidents",
            revocations: "accessRequestsAdminIncidents"
        }[active];
        return <Tabs activeKey={selectedTab} aria-label={t("accessRequestsAdminCatalog")} component="nav">
            <Tab eventKey={selectedTab} isDisabled title={<TabTitleText>{t(activeTitle)}</TabTitleText>} />
        </Tabs>;
    }

    return (
        <Tabs
            activeKey={selectedTab}
            aria-label={t("accessRequestsAdminCatalog")}
            component="nav"
            onSelect={(event, key) => {
                event.preventDefault();
                void navigate(paths[key as keyof typeof paths]);
            }}
        >
            {capabilities.canManageCatalog && <Tab eventKey="catalog" href={paths.catalog}
                title={<TabTitleText>{t("accessRequestsAdminCatalogTab")}</TabTitleText>} />}
            {capabilities.canManageAssurancePolicy && <Tab eventKey="assurance" href={paths.assurance}
                title={<TabTitleText>{t("accessRequestsAdminAssurancePolicy")}</TabTitleText>} />}
            {(capabilities.canManageNotifications || capabilities.canManageProvisioningFailures)
                && <Tab eventKey="incidents" href={paths.incidents}
                    title={<TabTitleText>{t("accessRequestsAdminIncidents")}</TabTitleText>} />}
            {capabilities.canViewEvents && <Tab eventKey="events" href={paths.events}
                title={<TabTitleText>{t("accessRequestsAdminEvents")}</TabTitleText>} />}
        </Tabs>
    );
}
