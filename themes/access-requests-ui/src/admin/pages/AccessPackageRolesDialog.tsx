import { Alert, Button, Form, Modal, ModalVariant, Text } from "@patternfly/react-core";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type AccessPackageDetails,
    type Entitlement,
    type EntitlementsAdminApi
} from "../api/EntitlementsAdminApi";
import { AccessPackageRoleSelector, type SelectedPackageRole } from "./AccessPackageRoleSelector";

export function AccessPackageRolesDialog({ api, details, entitlement, onClose, onSaved }: {
    api: EntitlementsAdminApi;
    details: AccessPackageDetails;
    entitlement: Entitlement;
    onClose: () => void;
    onSaved: () => void;
}) {
    const { t } = useTranslation();
    const [roles, setRoles] = useState<SelectedPackageRole[]>(details.roleMappings.map((role) => ({
        type: role.type, roleId: role.roleId, name: role.name ?? role.roleId
    })));
    const [error, setError] = useState<unknown>();
    const [validationError, setValidationError] = useState<string>();
    const [saving, setSaving] = useState(false);

    const save = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (saving) return;
        if (roles.length === 0) {
            setValidationError(t("accessRequestsAdminPackageAtLeastOneRole"));
            return;
        }
        setValidationError(undefined);
        setError(undefined);
        setSaving(true);
        try {
            await api.updateAccessPackageRoles(entitlement.id, entitlement.version,
                roles.map(({ type, roleId }) => ({ type, roleId })));
            onSaved();
        } catch (failure) {
            setError(failure);
        } finally {
            setSaving(false);
        }
    };
    const presentation = error ? presentEntitlementsAdminError(error) : undefined;

    return <Modal isOpen title={t("accessRequestsAdminPackageEditRoles")} variant={ModalVariant.large}
        onClose={() => { if (!saving) onClose(); }}
        actions={[
            <Button form="access-package-roles-form" isLoading={saving} key="save" type="submit">
                {t("accessRequestsAdminSave")}
            </Button>,
            <Button isDisabled={saving} key="cancel" onClick={onClose} variant="link">
                {t("accessRequestsAdminCancel")}
            </Button>
        ]}>
        <Text component="p">{t("accessRequestsAdminPackageEditRolesDescription")}</Text>
        {validationError && <Alert isInline variant="danger" title={validationError} />}
        {presentation && <Alert isInline variant="danger" title={presentation.requestId
            ? `${t(presentation.messageKey)} (${presentation.requestId})` : t(presentation.messageKey)} />}
        <Form id="access-package-roles-form" onSubmit={(event) => void save(event)}>
            <AccessPackageRoleSelector api={api} disabled={saving} idPrefix="edit-package"
                roles={roles} onChange={setRoles} />
        </Form>
    </Modal>;
}
