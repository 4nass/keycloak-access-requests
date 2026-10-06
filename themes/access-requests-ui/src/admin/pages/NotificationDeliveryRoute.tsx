import {
    Alert,
    Button,
    ButtonVariant,
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
import { NotificationDeliveryPage } from "./NotificationDeliveryPage";
import { AccessRequestsAdminPageFrame } from "./AccessRequestsAdminPageFrame";

/**
 * Keeps the server-owned manager authorization in front of operational controls.
 * The Admin Console route and navigation are conveniences only, never a security boundary.
 */
export function NotificationDeliveryRoute() {
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
                    setCanManage(capabilities.canManageNotifications);
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
        return <NotificationDeliveryPage />;
    }

    if (error) {
        const presentation = presentEntitlementsAdminError(error);
        const message = t(presentation.messageKey);
        return (
            <AccessRequestsAdminPageFrame active="notifications" titleKey="accessRequestsAdminNotificationDelivery"
                descriptionKey="accessRequestsAdminNotificationDeliveryDescription">
                <Alert
                    actionClose={<Button aria-label={t("close")} onClick={() => setRetry((value) => value + 1)} variant={ButtonVariant.plain} />}
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
            <AccessRequestsAdminPageFrame active="notifications" titleKey="accessRequestsAdminNotificationDelivery"
                descriptionKey="accessRequestsAdminNotificationDeliveryDescription">
                <EmptyState>
                    <EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminErrorForbidden")} />
                    <EmptyStateBody>{t("accessRequestsAdminNotificationDeliveryDescription")}</EmptyStateBody>
                </EmptyState>
            </AccessRequestsAdminPageFrame>
        );
    }

    return (
        <AccessRequestsAdminPageFrame active="notifications" titleKey="accessRequestsAdminNotificationDelivery"
            descriptionKey="accessRequestsAdminNotificationDeliveryDescription">
            <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
        </AccessRequestsAdminPageFrame>
    );
}
