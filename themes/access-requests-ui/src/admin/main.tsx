import "@keycloak/keycloak-admin-ui/styles.css";
import "@patternfly/patternfly/patternfly-addons.css";
import "@patternfly/react-core/dist/styles/base.css";

import { type AdminEnvironment, KeycloakProvider } from "@keycloak/keycloak-admin-ui";
import React from "react";
import ReactDOM from "react-dom/client";
import { createHashRouter, RouterProvider } from "react-router-dom";

import { environment } from "./environment";
import { adminI18nReady } from "./i18n";
import { routes } from "./routes";

const router = createHashRouter(routes);

void adminI18nReady.then(() => {
    ReactDOM.createRoot(document.getElementById("app")!).render(
        <React.StrictMode>
            <KeycloakProvider environment={environment as AdminEnvironment}>
                <RouterProvider router={router} />
            </KeycloakProvider>
        </React.StrictMode>
    );
});
