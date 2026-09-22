import { routes as keycloakRoutes } from "@keycloak/keycloak-admin-ui";
import { lazy } from "react";
import type { RouteObject } from "react-router-dom";

import { AccessRequestsAdminApp } from "./AccessRequestsAdminApp";

const EntitlementCatalogRoute = lazy(async () => {
    const module = await import("./pages/EntitlementCatalogRoute");

    return { default: module.EntitlementCatalogRoute };
});

const NotificationDeliveryRoute = lazy(async () => {
    const module = await import("./pages/NotificationDeliveryRoute");

    return { default: module.NotificationDeliveryRoute };
});

type AdminRoute = RouteObject & {
    handle?: {
        access: "anyone";
        breadcrumb?: (translate: (key: string) => string) => string;
    };
};

const entitlementCatalogRoute: AdminRoute = {
    path: "/:realm/access-requests",
    element: <EntitlementCatalogRoute />,
    handle: {
        // The server capability check is authoritative; the navigation only improves discovery.
        access: "anyone",
        breadcrumb: (translate) => translate("accessRequestsAdminCatalog")
    }
};

const notificationDeliveryRoute: AdminRoute = {
    path: "/:realm/access-requests/notification-deliveries",
    element: <NotificationDeliveryRoute />,
    handle: {
        // The server capability check is authoritative; the navigation only improves discovery.
        access: "anyone",
        breadcrumb: (translate) => translate("accessRequestsAdminNotificationDelivery")
    }
};

const notFoundRoute = keycloakRoutes.filter((route) => route.path === "*");
const standardRoutes = keycloakRoutes.filter((route) => route.path !== "*");

export const routes: RouteObject[] = [
    {
        path: "/",
        element: <AccessRequestsAdminApp />,
        children: [entitlementCatalogRoute, notificationDeliveryRoute, ...standardRoutes, ...notFoundRoute]
    }
];
