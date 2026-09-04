import { type AdminEnvironment, useEnvironment } from "@keycloak/keycloak-admin-ui";
import { useCallback, useMemo } from "react";

import { createEntitlementsAdminApi } from "./EntitlementsAdminApi";

export function useEntitlementsAdminApi() {
    const { environment, keycloak } = useEnvironment<AdminEnvironment>();
    const getAccessToken = useCallback(async () => {
        if (!keycloak) {
            throw new Error("The Administration Console access token is unavailable.");
        }

        await keycloak.updateToken(30);
        if (!keycloak.token) {
            throw new Error("The Administration Console access token is unavailable.");
        }
        return keycloak.token;
    }, [keycloak]);

    return useMemo(
        () => createEntitlementsAdminApi({
            fetch,
            getAccessToken,
            realm: environment.realm,
            serverBaseUrl: environment.serverBaseUrl
        }),
        [environment.realm, environment.serverBaseUrl, getAccessToken]
    );
}
