import {
    Applications,
    DeviceActivity,
    Groups,
    LinkedAccounts,
    PersonalInfo,
    Resources,
    SigningIn
} from "@keycloak/keycloak-account-ui";
import type { RouteObject } from "react-router-dom";

import { App } from "./App";
import { environment } from "./environment";
import { ApprovalsRoutePage, MyRequestsRoutePage, RequestAccessRoutePage } from "./pages/AccessRequestRoutePages";

export const routes: RouteObject[] = [
    {
        path: decodeURIComponent(new URL(environment.baseUrl).pathname),
        element: <App />,
        errorElement: <>Unable to load the Account Console.</>,
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
