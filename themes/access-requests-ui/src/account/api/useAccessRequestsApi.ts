import { type AccountEnvironment, useEnvironment } from "@keycloak/keycloak-account-ui";
import { useCallback, useMemo } from "react";

import { createAccessRequestsApi } from "./AccessRequestsApi";

export function useAccessRequestsApi() {
    const { environment, keycloak } = useEnvironment<AccountEnvironment>();
    const getAccessToken = useCallback(async () => {
        await keycloak.updateToken(30);
        if (!keycloak.token) {
            throw new Error("The Account Console access token is unavailable.");
        }
        return keycloak.token;
    }, [keycloak]);

    return useMemo(
        () => createAccessRequestsApi({
            fetch,
            getAccessToken,
            realm: environment.realm,
            serverBaseUrl: environment.serverBaseUrl
        }),
        [environment.realm, environment.serverBaseUrl, getAccessToken]
    );
}
