import { Alert, Button, EmptyState, EmptyStateBody, EmptyStateHeader, Spinner } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import { isEntitlementsAdminAuthorizationError, presentEntitlementsAdminError } from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { RevocationFailuresPage } from "./RevocationFailuresPage";
import { AccessRequestsAdminPageFrame } from "./AccessRequestsAdminPageFrame";

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
        return <AccessRequestsAdminPageFrame active="revocations" titleKey="accessRequestsAdminRevocationFailures" descriptionKey="accessRequestsAdminRevocationFailuresDescription"><Alert isInline variant="danger" title={t(presentation.messageKey)}
            actionLinks={<Button variant="link" onClick={() => setRetry((value) => value + 1)}>{t("reload")}</Button>} /></AccessRequestsAdminPageFrame>;
    }
    if (canManage === false) {
        return <AccessRequestsAdminPageFrame active="revocations" titleKey="accessRequestsAdminRevocationFailures" descriptionKey="accessRequestsAdminRevocationFailuresDescription"><EmptyState><EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminErrorForbidden")} />
            <EmptyStateBody>{t("accessRequestsAdminRevocationFailuresDescription")}</EmptyStateBody>
        </EmptyState></AccessRequestsAdminPageFrame>;
    }
    return <AccessRequestsAdminPageFrame active="revocations" titleKey="accessRequestsAdminRevocationFailures" descriptionKey="accessRequestsAdminRevocationFailuresDescription"><EmptyState><Spinner aria-label={t("loading")} /></EmptyState></AccessRequestsAdminPageFrame>;
}
