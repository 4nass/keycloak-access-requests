import { routes as keycloakRoutes } from "@keycloak/keycloak-admin-ui";
import type { RouteObject } from "react-router-dom";

import { AccessRequestsAdminApp } from "./AccessRequestsAdminApp";
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
        // The server capability check is authoritative; the navigation only improves discovery.
        access: "anyone",
        breadcrumb: (translate) => translate("accessRequestsAdminCatalog")
    }
};

const notFoundRoute = keycloakRoutes.filter((route) => route.path === "*");
const standardRoutes = keycloakRoutes.filter((route) => route.path !== "*");

export const routes: RouteObject[] = [
    {
        path: "/",
        element: <AccessRequestsAdminApp />,
        children: [entitlementCatalogRoute, ...standardRoutes, ...notFoundRoute]
    }
];
