import { Alert, Button, EmptyState, EmptyStateBody, EmptyStateHeader, Spinner } from "@patternfly/react-core";
import { useEffect, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { isEntitlementsAdminAuthorizationError, presentEntitlementsAdminError } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { AccessRequestsAdminPageFrame } from "./AccessRequestsAdminPageFrame";

export function AuthorizedAuditPage({ children }: { children: ReactNode }) {
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
            if (active) setAllowed(capabilities.canViewEvents === true);
        }).catch((failure: unknown) => {
            if (!active) return;
            if (isEntitlementsAdminAuthorizationError(failure)) setAllowed(false);
            else setError(failure);
        });
        return () => { active = false; };
    }, [api, retry]);

    if (allowed) return <>{children}</>;
    if (error) {
        const presentation = presentEntitlementsAdminError(error);
        const message = t(presentation.messageKey);
        return <AccessRequestsAdminPageFrame active="events" titleKey="accessRequestsAdminEvents" descriptionKey="accessRequestsAdminEventsDescription"><Alert isInline variant="danger"
            title={presentation.requestId ? `${message} (${presentation.requestId})` : message}
            actionLinks={<Button variant="link" onClick={() => setRetry((value) => value + 1)}>{t("reload")}</Button>}
        /></AccessRequestsAdminPageFrame>;
    }
    if (allowed === false) return <AccessRequestsAdminPageFrame active="events" titleKey="accessRequestsAdminEvents" descriptionKey="accessRequestsAdminEventsDescription"><EmptyState>
        <EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminErrorForbidden")} />
        <EmptyStateBody>{t("accessRequestsAdminEventsDescription")}</EmptyStateBody>
    </EmptyState></AccessRequestsAdminPageFrame>;
    return <AccessRequestsAdminPageFrame active="events" titleKey="accessRequestsAdminEvents" descriptionKey="accessRequestsAdminEventsDescription"><EmptyState><Spinner aria-label={t("loading")} /></EmptyState></AccessRequestsAdminPageFrame>;
}
