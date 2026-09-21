import { AdminUi, routes as keycloakRoutes } from "@keycloak/keycloak-admin-ui";
import type { RouteObject } from "react-router-dom";

import { EntitlementCatalogRoute } from "./pages/EntitlementCatalogRoute";

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
        // The server capability check is authoritative. UiPageProvider navigation has no per-user access hook.
        access: "anyone",
        breadcrumb: (translate) => translate("accessRequestsAdminCatalog")
    }
};

const entitlementCatalogNavigationRoute: AdminRoute = {
    ...entitlementCatalogRoute,
    path: "/:realm/page-section/access-requests"
};

const notFoundRoute = keycloakRoutes.filter((route) => route.path === "*");
const standardRoutes = keycloakRoutes.filter((route) => route.path !== "*");

export const routes: RouteObject[] = [
    {
        path: "/",
        element: <AdminUi />,
        // UiPageProvider links to page-section/access-requests. It must precede Keycloak's generic page-section route.
        children: [entitlementCatalogNavigationRoute, ...standardRoutes, entitlementCatalogRoute, ...notFoundRoute]
    }
];
