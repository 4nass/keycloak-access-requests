import {
    Alert, Button, DataList, DataListAction, DataListCell, DataListItem, DataListItemCells,
    DataListItemRow, FormGroup, FormSelect, FormSelectOption, Grid, GridItem, Label
} from "@patternfly/react-core";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import type { AccessPackageRole, EntitlementsAdminApi } from "../api/EntitlementsAdminApi";
import { KeycloakReferenceSelector } from "./KeycloakReferenceSelector";

export type SelectedPackageRole = AccessPackageRole & { name: string };

export function AccessPackageRoleSelector({ api, disabled, idPrefix, onChange, roles }: {
    api: EntitlementsAdminApi;
    disabled: boolean;
    idPrefix: string;
    onChange: (roles: SelectedPackageRole[]) => void;
    roles: SelectedPackageRole[];
}) {
    const { t } = useTranslation();
    const [candidateType, setCandidateType] = useState<AccessPackageRole["type"]>("REALM_ROLE");
    const [candidateId, setCandidateId] = useState("");
    const [candidateName, setCandidateName] = useState("");
    const [validationError, setValidationError] = useState<string>();

    const add = () => {
        if (!candidateId) {
            setValidationError(t("accessRequestsAdminPackageSelectRoleFirst"));
        } else if (roles.some((role) => role.type === candidateType && role.roleId === candidateId)) {
            setValidationError(t("accessRequestsAdminPackageDuplicateRole"));
        } else if (roles.length >= 100) {
            setValidationError(t("accessRequestsAdminPackageRoleLimit"));
        } else {
            onChange([...roles, { type: candidateType, roleId: candidateId, name: candidateName || candidateId }]);
            setCandidateId("");
            setCandidateName("");
            setValidationError(undefined);
        }
    };

    return <>
        {validationError && <Alert isInline variant="danger" title={validationError} />}
        <Grid hasGutter>
            <GridItem sm={4}>
                <FormGroup fieldId={`${idPrefix}-type`} label={t("accessRequestsAdminPackageRoles")}>
                    <FormSelect id={`${idPrefix}-type`} isDisabled={disabled} value={candidateType}
                        onChange={(_event, value) => {
                            setCandidateType(value as AccessPackageRole["type"]);
                            setCandidateId("");
                            setCandidateName("");
                        }}>
                        <FormSelectOption label={t("accessRequestsAdminResourceTypeRealmRole")} value="REALM_ROLE" />
                        <FormSelectOption label={t("accessRequestsAdminResourceTypeClientRole")} value="CLIENT_ROLE" />
                    </FormSelect>
                </FormGroup>
            </GridItem>
            <GridItem sm={8}>
                <FormGroup fieldId={`${idPrefix}-role`} label={t("accessRequestsAdminPackageAddRole")}>
                    <KeycloakReferenceSelector api={api} fieldId={`${idPrefix}-role`} isDisabled={disabled}
                        isRequired={false} key={candidateType} resourceType={candidateType}
                        onSelect={(id, reference) => {
                            setCandidateId(id);
                            setCandidateName(reference?.name ?? id);
                        }}
                        searchLabel="accessRequestsAdminPackageSearchRoles"
                        searchPlaceholder="accessRequestsAdminSearchResourcesPlaceholder"
                        selectionPlaceholder="accessRequestsAdminPackageSelectRole" value={candidateId} />
                    <Button isDisabled={disabled || !candidateId} onClick={add} type="button" variant="secondary"
                        className="pf-v5-u-mt-sm">{t("accessRequestsAdminPackageAddRole")}</Button>
                </FormGroup>
            </GridItem>
        </Grid>
        {roles.length > 0 && <DataList aria-label={t("accessRequestsAdminPackageSelectedRoles")}>
            {roles.map((role) => {
                const roleKey = `${role.type}-${role.roleId}`;
                return <DataListItem key={roleKey}>
                    <DataListItemRow>
                        <DataListItemCells dataListCells={[<DataListCell id={`${idPrefix}-${roleKey}`} key="role">
                            <Label>{t(role.type === "REALM_ROLE"
                                ? "accessRequestsAdminResourceTypeRealmRole" : "accessRequestsAdminResourceTypeClientRole")}</Label>
                            {" "}{role.name}
                        </DataListCell>]} />
                        <DataListAction aria-label={role.name} aria-labelledby={`${idPrefix}-${roleKey}`}
                            id={`${idPrefix}-remove-${roleKey}`}>
                            <Button aria-label={`${t("accessRequestsAdminPackageRemoveRole")} ${role.name}`}
                                isDisabled={disabled} type="button" variant="link"
                                onClick={() => onChange(roles.filter((item) =>
                                    item.type !== role.type || item.roleId !== role.roleId))}>
                                {t("accessRequestsAdminPackageRemoveRole")}
                            </Button>
                        </DataListAction>
                    </DataListItemRow>
                </DataListItem>;
            })}
        </DataList>}
    </>;
}
