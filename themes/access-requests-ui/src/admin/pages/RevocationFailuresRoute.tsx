import { Alert, Button, EmptyState, EmptyStateBody, EmptyStateHeader, PageSection, Spinner } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import { isEntitlementsAdminAuthorizationError, presentEntitlementsAdminError } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { RevocationFailuresPage } from "./RevocationFailuresPage";

export function RevocationFailuresRoute() {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [canManage, setCanManage] = useState<boolean>();
    const [error, setError] = useState<unknown>();
    const [retry, setRetry] = useState(0);

    useEffect(() => {
        let active = true;
        setCanManage(undefined);
        setError(undefined);
        void api.capabilities().then((capabilities) => {
            if (active) setCanManage(capabilities.canManageProvisioningFailures);
        }).catch((failure: unknown) => {
            if (!active) return;
            if (isEntitlementsAdminAuthorizationError(failure)) setCanManage(false);
            else setError(failure);
        });
        return () => { active = false; };
    }, [api, retry]);

    if (canManage) return <RevocationFailuresPage />;
    if (error) {
        const presentation = presentEntitlementsAdminError(error);
        return <PageSection><Alert isInline variant="danger" title={t(presentation.messageKey)}
            actionLinks={<Button variant="link" onClick={() => setRetry((value) => value + 1)}>{t("reload")}</Button>} /></PageSection>;
    }
    if (canManage === false) {
        return <PageSection><EmptyState><EmptyStateHeader headingLevel="h1" titleText={t("accessRequestsAdminErrorForbidden")} />
            <EmptyStateBody>{t("accessRequestsAdminRevocationFailuresDescription")}</EmptyStateBody>
        </EmptyState></PageSection>;
    }
    return <PageSection><EmptyState><Spinner aria-label={t("loading")} /></EmptyState></PageSection>;
}
