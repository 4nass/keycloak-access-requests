import { type AdminEnvironment, useEnvironment, useRealm } from "@keycloak/keycloak-admin-ui";
import { useCallback, useMemo } from "react";

import { createEntitlementsAdminApi } from "./EntitlementsAdminApi";

export function useEntitlementsAdminApi() {
    const { environment, keycloak } = useEnvironment<AdminEnvironment>();
    const { realm } = useRealm();
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
            realm,
            serverBaseUrl: environment.serverBaseUrl
        }),
        [realm, environment.serverBaseUrl, getAccessToken]
    );
}
