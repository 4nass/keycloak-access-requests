import { useAccountAlerts } from "@keycloak/keycloak-account-ui";

export function useAccessRequestAlerts() {
    return useAccountAlerts();
}
