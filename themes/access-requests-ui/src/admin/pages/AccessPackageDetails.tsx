import {
    Alert, DataList, DataListCell, DataListItem, DataListItemCells, DataListItemRow,
    Label, Spinner, Text, Title
} from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type EntitlementsAdminApi,
    type AccessPackageDetails as Details
} from "../api/EntitlementsAdminApi";

export function AccessPackageDetails({ api, entitlementId, onValidityChange }: {
    api: EntitlementsAdminApi;
    entitlementId: string;
    onValidityChange: (valid: boolean | undefined) => void;
}) {
    const { t } = useTranslation();
    const [details, setDetails] = useState<Details | null>();
    const [error, setError] = useState<unknown>();

    useEffect(() => {
        let active = true;
        setDetails(undefined);
        setError(undefined);
        onValidityChange(undefined);
        void api.getAccessPackage(entitlementId).then((result) => {
            if (active) {
                setDetails(result);
                onValidityChange(result !== null && result.configurationValid);
            }
        }).catch((failure: unknown) => {
            if (active) {
                setError(failure);
                onValidityChange(false);
            }
        });
        return () => { active = false; };
    }, [api, entitlementId, onValidityChange]);

    if (error) {
        const presentation = presentEntitlementsAdminError(error);
        return <Alert isInline variant="danger" title={
            `${t(presentation.messageKey)}${presentation.requestId ? ` (${presentation.requestId})` : ""}`
        } />;
    }
    if (details === undefined) {
        return <Spinner aria-label={t("loading")} />;
    }
    if (details === null) {
        return <Alert isInline variant="info" title={t("accessRequestsAdminDirectEntitlementDraftOnly")} />;
    }

    return <section aria-label={t("accessRequestsAdminPackageDetails")}>
        <Title headingLevel="h3" size="md">{t("accessRequestsAdminPackageDetails")}</Title>
        {!details.groupExists && <Alert isInline variant="danger"
            title={t("accessRequestsAdminPackageMissingGroup")} className="pf-v5-u-mt-sm" />}
        {details.groupExists && !details.configurationValid && <Alert isInline variant="danger"
            title={t("accessRequestsAdminPackageInvalidConfiguration")} className="pf-v5-u-mt-sm" />}
        <Title headingLevel="h4" size="md" className="pf-v5-u-mt-md">
            {t("accessRequestsAdminPackageRoles")}
        </Title>
        <DataList aria-label={t("accessRequestsAdminPackageRoles")}>
            {details.roleMappings.map((role) => <DataListItem key={`${role.type}:${role.roleId}`}>
                <DataListItemRow>
                    <DataListItemCells dataListCells={[<DataListCell key="role">
                        <Label>{t(role.type === "REALM_ROLE"
                            ? "accessRequestsAdminResourceTypeRealmRole" : "accessRequestsAdminResourceTypeClientRole")}</Label>
                        {" "}{role.name ?? role.roleId}
                        {role.name && <Text component="small">({role.roleId})</Text>}
                        {role.missing && <Label color="red">{t("accessRequestsAdminPackageMissingRole")}</Label>}
                    </DataListCell>]} />
                </DataListItemRow>
            </DataListItem>)}
        </DataList>
    </section>;
}
