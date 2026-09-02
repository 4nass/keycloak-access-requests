import { Header } from "@keycloak/keycloak-account-ui";
import { Page, Spinner } from "@patternfly/react-core";
import { Suspense } from "react";
import { Outlet } from "react-router-dom";

import { PageNav } from "./PageNav";

export function App() {
    return (
        <Page header={<Header />} sidebar={<PageNav />} isManagedSidebar>
            <Suspense fallback={<Spinner />}>
                <Outlet />
            </Suspense>
        </Page>
    );
}
