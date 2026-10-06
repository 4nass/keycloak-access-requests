import {
    Alert,
    Button,
    EmptyState,
    EmptyStateBody,
    EmptyStateHeader,
    Spinner
} from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    isEntitlementsAdminAuthorizationError,
    presentEntitlementsAdminError
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { FailedProvisioningPage } from "./FailedProvisioningPage";
import { AccessRequestsAdminPageFrame } from "./AccessRequestsAdminPageFrame";

export function FailedProvisioningRoute() {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [canManage, setCanManage] = useState<boolean>();
    const [error, setError] = useState<unknown>();
    const [retry, setRetry] = useState(0);

    useEffect(() => {
        let active = true;
        setCanManage(undefined);
        setError(undefined);

        void api.capabilities()
            .then((capabilities) => {
                if (active) {
                    setCanManage(capabilities.canManageProvisioningFailures === true);
                }
            })
            .catch((capabilityError: unknown) => {
                if (!active) {
                    return;
                }
                if (isEntitlementsAdminAuthorizationError(capabilityError)) {
                    setCanManage(false);
                    return;
                }
                setError(capabilityError);
            });

        return () => {
            active = false;
        };
    }, [api, retry]);

    if (canManage) {
        return <FailedProvisioningPage />;
    }

    if (error) {
        const presentation = presentEntitlementsAdminError(error);
        const message = t(presentation.messageKey);
        return (
            <AccessRequestsAdminPageFrame active="provisioning" titleKey="accessRequestsAdminFailedProvisioning" descriptionKey="accessRequestsAdminFailedProvisioningDescription">
                <Alert
                    actionLinks={<Button onClick={() => setRetry((value) => value + 1)} variant="link">{t("reload")}</Button>}
                    isInline
                    title={presentation.requestId ? `${message} (${presentation.requestId})` : message}
                    variant="danger"
                />
            </AccessRequestsAdminPageFrame>
        );
    }

    if (canManage === false) {
        return (
            <AccessRequestsAdminPageFrame active="provisioning" titleKey="accessRequestsAdminFailedProvisioning" descriptionKey="accessRequestsAdminFailedProvisioningDescription">
                <EmptyState>
                    <EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminErrorForbidden")} />
                    <EmptyStateBody>{t("accessRequestsAdminFailedProvisioningDescription")}</EmptyStateBody>
                </EmptyState>
            </AccessRequestsAdminPageFrame>
        );
    }

    return <AccessRequestsAdminPageFrame active="provisioning" titleKey="accessRequestsAdminFailedProvisioning" descriptionKey="accessRequestsAdminFailedProvisioningDescription"><EmptyState><Spinner aria-label={t("loading")} /></EmptyState></AccessRequestsAdminPageFrame>;
}
