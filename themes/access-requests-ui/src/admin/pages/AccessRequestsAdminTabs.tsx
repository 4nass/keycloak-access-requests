import { Tab, Tabs, TabTitleText } from "@patternfly/react-core";
import { useTranslation } from "react-i18next";
import { useInRouterContext, useLocation, useNavigate } from "react-router-dom";

export function AccessRequestsAdminTabs({ active }: { active: "catalog" | "events" }) {
    // The catalog is also rendered in isolated component tests without a router.
    return useInRouterContext() ? <RoutedTabs active={active} /> : null;
}

function RoutedTabs({ active }: { active: "catalog" | "events" }) {
    const { t } = useTranslation();
    const { pathname } = useLocation();
    const navigate = useNavigate();
    const marker = "/access-requests";
    const markerIndex = pathname.indexOf(marker);
    const base = markerIndex < 0 ? pathname : pathname.slice(0, markerIndex + marker.length);
    const paths = { catalog: base, events: `${base}/events` };

    return (
        <Tabs
            activeKey={active}
            aria-label={t("accessRequestsAdminCatalog")}
            component="nav"
            onSelect={(event, key) => {
                event.preventDefault();
                void navigate(paths[key === "events" ? "events" : "catalog"]);
            }}
        >
            <Tab eventKey="catalog" href={paths.catalog} title={<TabTitleText>{t("accessRequestsAdminCatalog")}</TabTitleText>} />
            <Tab eventKey="events" href={paths.events} title={<TabTitleText>{t("accessRequestsAdminEvents")}</TabTitleText>} />
        </Tabs>
    );
}
