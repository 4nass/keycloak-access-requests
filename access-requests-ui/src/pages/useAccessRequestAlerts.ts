import { useAccountAlerts } from "@keycloak/keycloak-account-ui";
import { AlertVariant } from "@patternfly/react-core";
import { useTranslation } from "react-i18next";

import { presentAccessRequestsError } from "../api/AccessRequestsApi";

export function useAccessRequestAlerts() {
    const { addAlert } = useAccountAlerts();
    const { t } = useTranslation();

    return {
        addAlert,
        addError(error: unknown) {
            const presentation = presentAccessRequestsError(error);
            addAlert(
                t(presentation.messageKey),
                AlertVariant.danger,
                presentation.requestId ? t("accessRequestsErrorReference", { requestId: presentation.requestId }) : undefined
            );
        }
    };
}
