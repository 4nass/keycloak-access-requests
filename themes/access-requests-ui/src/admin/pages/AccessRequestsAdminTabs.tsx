import { Tab, Tabs, TabTitleText } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useInRouterContext, useLocation, useNavigate } from "react-router-dom";

import type { AdminCapabilities } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";

export type AdminTab = "catalog" | "assurance" | "events" | "notifications" | "provisioning" | "revocations";

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

    const marker = "/access-requests";
    const markerIndex = pathname.indexOf(marker);
    const base = markerIndex < 0 ? pathname : pathname.slice(0, markerIndex + marker.length);
    const paths = {
        catalog: base,
        assurance: `${base}/assurance-policy`,
        events: `${base}/events`,
        notifications: `${base}/notification-deliveries`,
        provisioning: `${base}/provisioning-failures`,
        revocations: `${base}/revocation-failures`
    };

    if (!capabilities || !Object.values(capabilities).some(Boolean)) {
        const activeTitle = {
            catalog: "accessRequestsAdminCatalogTab",
            assurance: "accessRequestsAdminAssurancePolicy",
            events: "accessRequestsAdminEvents",
            notifications: "accessRequestsAdminNotificationDelivery",
            provisioning: "accessRequestsAdminFailedProvisioning",
            revocations: "accessRequestsAdminRevocationFailures"
        }[active];
        return <Tabs activeKey={active} aria-label={t("accessRequestsAdminCatalog")} component="nav">
            <Tab eventKey={active} isDisabled title={<TabTitleText>{t(activeTitle)}</TabTitleText>} />
        </Tabs>;
    }

    return (
        <Tabs
            activeKey={active}
            aria-label={t("accessRequestsAdminCatalog")}
            component="nav"
            onSelect={(event, key) => {
                event.preventDefault();
                void navigate(paths[key as AdminTab]);
            }}
        >
            {capabilities.canManageCatalog && <Tab eventKey="catalog" href={paths.catalog}
                title={<TabTitleText>{t("accessRequestsAdminCatalogTab")}</TabTitleText>} />}
            {capabilities.canManageAssurancePolicy && <Tab eventKey="assurance" href={paths.assurance}
                title={<TabTitleText>{t("accessRequestsAdminAssurancePolicy")}</TabTitleText>} />}
            {capabilities.canManageNotifications && <Tab eventKey="notifications" href={paths.notifications}
                title={<TabTitleText>{t("accessRequestsAdminNotificationDelivery")}</TabTitleText>} />}
            {capabilities.canManageProvisioningFailures && <Tab eventKey="provisioning" href={paths.provisioning}
                title={<TabTitleText>{t("accessRequestsAdminFailedProvisioning")}</TabTitleText>} />}
            {capabilities.canManageProvisioningFailures && <Tab eventKey="revocations" href={paths.revocations}
                title={<TabTitleText>{t("accessRequestsAdminRevocationFailures")}</TabTitleText>} />}
            {capabilities.canViewEvents && <Tab eventKey="events" href={paths.events}
                title={<TabTitleText>{t("accessRequestsAdminEvents")}</TabTitleText>} />}
        </Tabs>
    );
}
