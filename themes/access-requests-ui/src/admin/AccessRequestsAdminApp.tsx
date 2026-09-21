import {
    AdminClientProvider,
    AppContexts,
    ForbiddenSection,
    Header,
    PageBreadCrumbs,
    useAccess,
    useWhoAmI
} from "@keycloak/keycloak-admin-ui";
import {
    Banner,
    Flex,
    FlexItem,
    Page,
    PageSection,
    Spinner
} from "@patternfly/react-core";
import { Suspense, type PropsWithChildren, useEffect } from "react";
import { Outlet, useMatches } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { AccessRequestsAdminPageNav } from "./AccessRequestsAdminPageNav";

type RouteAccess = Parameters<ReturnType<typeof useAccess>["hasAccess"]>[number];
const MAIN_PAGE_CONTENT_ID = "kc-main-content-page-container";

type RouteHandle = {
    access: RouteAccess | RouteAccess[];
};

/**
 * Owns the Admin Console shell so Access requests navigation stays within the supported React theme API.
 */
export function AccessRequestsAdminApp() {
    const hrefEndsWithHashSlash = location.href.endsWith("#/");

    useEffect(() => {
        if (hrefEndsWithHashSlash) {
            history.replaceState(null, "", location.pathname);
        }
    }, [hrefEndsWithHashSlash]);

    return (
        <AdminClientProvider>
            <AppContexts>
                <Flex direction={{ default: "column" }} flexWrap={{ default: "nowrap" }} spaceItems={{ default: "spaceItemsNone" }} style={{ height: "100%" }}>
                    <FlexItem><TemporaryAdminBanner /></FlexItem>
                    <FlexItem grow={{ default: "grow" }} style={{ minHeight: 0 }}>
                        <Page
                            breadcrumb={<PageBreadCrumbs />}
                            header={<Header />}
                            isManagedSidebar
                            mainContainerId={MAIN_PAGE_CONTENT_ID}
                            sidebar={<AccessRequestsAdminPageNav />}
                        >
                            <Suspense fallback={<AdminLoading />}>
                                <AdminRouteAuthorization><Outlet /></AdminRouteAuthorization>
                            </Suspense>
                        </Page>
                    </FlexItem>
                </Flex>
            </AppContexts>
        </AdminClientProvider>
    );
}

function TemporaryAdminBanner() {
    const { t } = useTranslation();
    const { whoAmI } = useWhoAmI();

    if (!whoAmI.temporary) {
        return null;
    }

    return (
        <Banner screenReaderText={t("loggedInAsTempAdminUser")} variant="gold">
            {t("loggedInAsTempAdminUser")}
        </Banner>
    );
}

function AdminLoading() {
    const { t } = useTranslation();

    return (
        <PageSection>
            <Spinner aria-label={t("loading")} />
        </PageSection>
    );
}

function AdminRouteAuthorization({ children }: PropsWithChildren) {
    const matches = useMatches();
    const { hasAccess } = useAccess();
    const permissions = matches.flatMap(({ handle }) => routePermissions(handle));

    return hasAccess(...permissions)
        ? children
        : <ForbiddenSection permissionNeeded={permissions} />;
}

function routePermissions(handle: unknown): RouteAccess[] {
    if (!isRouteHandle(handle)) {
        return [];
    }

    return Array.isArray(handle.access) ? handle.access : [handle.access];
}

function isRouteHandle(handle: unknown): handle is RouteHandle {
    return typeof handle === "object" && handle !== null && "access" in handle;
}
