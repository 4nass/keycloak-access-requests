import "@patternfly/patternfly/patternfly-addons.css";
import "@patternfly/react-core/dist/styles/base.css";

// Keycloak ships these native Account Console styles without exporting a CSS subpath.
import "../../node_modules/@keycloak/keycloak-account-ui/lib/keycloak-account-ui.css";

import { KeycloakProvider } from "@keycloak/keycloak-account-ui";
import React from "react";
import ReactDOM from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router-dom";

import { environment } from "./environment";
import { i18n } from "./i18n";
import { routes } from "./routes";

const router = createBrowserRouter(routes);

void i18n.init().then(() => {
    ReactDOM.createRoot(document.getElementById("app")!).render(
        <React.StrictMode>
            <KeycloakProvider environment={environment}>
                <RouterProvider router={router} />
            </KeycloakProvider>
        </React.StrictMode>
    );
});
