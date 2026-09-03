import { Alert, AlertActionLink, PageSection } from "@patternfly/react-core";
import { useTranslation } from "react-i18next";

export function AccountConsoleError() {
    const { t } = useTranslation();

    return (
        <PageSection isWidthLimited>
            <Alert
                actionLinks={
                    <AlertActionLink onClick={() => window.location.reload()}>
                        {t("accessRequestsRetry")}
                    </AlertActionLink>
                }
                isInline
                role="alert"
                title={t("accessRequestsAccountConsoleLoadError")}
                variant="danger"
            >
                {t("accessRequestsAccountConsoleLoadErrorDescription")}
            </Alert>
        </PageSection>
    );
}
