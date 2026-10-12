import { Alert, Button, EmptyState, EmptyStateBody, EmptyStateHeader, Spinner } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    isEntitlementsAdminAuthorizationError,
    presentEntitlementsAdminError
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { AccessRequestsAdminPageFrame } from "./AccessRequestsAdminPageFrame";
import { IncidentsPage } from "./IncidentsPage";

export function IncidentsRoute() {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [allowed, setAllowed] = useState<boolean>();
    const [error, setError] = useState<unknown>();
    const [retry, setRetry] = useState(0);

    useEffect(() => {
        let active = true;
        setAllowed(undefined);
        setError(undefined);
        void api.capabilities().then((capabilities) => {
            if (active) {
                setAllowed(capabilities.canManageNotifications || capabilities.canManageProvisioningFailures);
            }
        }).catch((failure: unknown) => {
            if (!active) return;
            if (isEntitlementsAdminAuthorizationError(failure)) setAllowed(false);
            else setError(failure);
        });
        return () => { active = false; };
    }, [api, retry]);

    let content;
    if (allowed) {
        content = <IncidentsPage />;
    } else if (error) {
        const presentation = presentEntitlementsAdminError(error);
        content = <Alert isInline variant="danger"
            title={`${t(presentation.messageKey)}${presentation.requestId ? ` (${presentation.requestId})` : ""}`}
            actionLinks={<Button variant="link" onClick={() => setRetry((value) => value + 1)}>{t("reload")}</Button>} />;
    } else if (allowed === false) {
        content = <EmptyState><EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminErrorForbidden")} />
            <EmptyStateBody>{t("accessRequestsAdminIncidentsDescription")}</EmptyStateBody></EmptyState>;
    } else {
        content = <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>;
    }

    return <AccessRequestsAdminPageFrame active="incidents" titleKey="accessRequestsAdminIncidents"
        descriptionKey="accessRequestsAdminIncidentsDescription">{content}</AccessRequestsAdminPageFrame>;
}
