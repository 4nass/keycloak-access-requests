import { Header } from "@keycloak/keycloak-account-ui";
import { Page, Spinner } from "@patternfly/react-core";
import { Suspense, type ReactNode } from "react";
import { Outlet } from "react-router-dom";

import { PageNav } from "./PageNav";

type AppProps = {
    children?: ReactNode;
};

export function App({ children }: AppProps) {
    return (
        <Page header={<Header />} sidebar={<PageNav />} isManagedSidebar>
            <Suspense fallback={<Spinner />}>
                {children ?? <Outlet />}
            </Suspense>
        </Page>
    );
}
