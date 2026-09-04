import {
    Applications,
    DeviceActivity,
    Groups,
    LinkedAccounts,
    PersonalInfo,
    Resources,
    SigningIn
} from "@keycloak/keycloak-account-ui";
import { lazy } from "react";
import type { RouteObject } from "react-router-dom";

import { AccountConsoleError } from "./AccountConsoleError";
import { App } from "./App";
import { environment } from "./environment";

const RequestAccessRoutePage = lazy(() => import("./pages/RequestAccessRoutePage"));
const MyRequestsRoutePage = lazy(() => import("./pages/MyRequestsRoutePage"));
const ApprovalsRoutePage = lazy(() => import("./pages/ApprovalsRoutePage"));

export const routes: RouteObject[] = [
    {
        path: decodeURIComponent(new URL(environment.baseUrl).pathname),
        element: <App />,
        errorElement: <App><AccountConsoleError /></App>,
        children: [
            { index: true, element: <PersonalInfo /> },
            { path: "personal-info", element: <PersonalInfo /> },
            { path: "account-security/device-activity", element: <DeviceActivity /> },
            { path: "account-security/linked-accounts", element: <LinkedAccounts /> },
            { path: "account-security/signing-in", element: <SigningIn /> },
            { path: "applications", element: <Applications /> },
            { path: "groups", element: <Groups /> },
            { path: "resources", element: <Resources /> },
            { path: "request-access", element: <RequestAccessRoutePage /> },
            { path: "my-requests", element: <MyRequestsRoutePage /> },
            { path: "approvals", element: <ApprovalsRoutePage /> }
        ]
    }
];
