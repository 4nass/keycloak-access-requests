import { PageSection, Text, TextContent, Title } from "@patternfly/react-core";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { AccessRequestsAdminTabs, type AdminTab } from "./AccessRequestsAdminTabs";

type AdminPageFrameProps = {
    active: AdminTab;
    titleKey: string;
    descriptionKey: string;
    children: ReactNode;
};

/** Keeps the page heading and navigation in place while a route checks permissions or recovers from an error. */
export function AccessRequestsAdminPageFrame({ active, titleKey, descriptionKey, children }: AdminPageFrameProps) {
    const { t } = useTranslation();

    return <>
        <PageSection variant="light">
            <Title headingLevel="h1">{t(titleKey)}</Title>
            <TextContent><Text component="p">{t(descriptionKey)}</Text></TextContent>
        </PageSection>
        <PageSection>
            <AccessRequestsAdminTabs active={active} />
            {children}
        </PageSection>
    </>;
}
