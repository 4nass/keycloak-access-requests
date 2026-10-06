import {
    Alert, Button, Checkbox, Form, FormGroup, FormSelect, FormSelectOption, Modal, ModalVariant,
    Text, TextArea, TextInput
} from "@patternfly/react-core";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type Entitlement,
    type EntitlementsAdminApi
} from "../api/EntitlementsAdminApi";
import { DURATION_PRESETS, durationSeconds, type DurationUnit } from "./catalogDuration";
import { DurationField } from "./DurationField";
import { KeycloakReferenceSelector } from "./KeycloakReferenceSelector";
import { AccessPackageRoleSelector, type SelectedPackageRole } from "./AccessPackageRoleSelector";

export function AccessPackageDialog({ api, onClose, onCreated }: {
    api: EntitlementsAdminApi;
    onClose: () => void;
    onCreated: (entitlement: Entitlement) => void;
}) {
    const { t } = useTranslation();
    const [displayName, setDisplayName] = useState("");
    const [description, setDescription] = useState("");
    const [riskLevel, setRiskLevel] = useState<Entitlement["riskLevel"]>("LOW");
    const [approverRoleId, setApproverRoleId] = useState("");
    const [defaultAmount, setDefaultAmount] = useState(DURATION_PRESETS.LOW.defaultDurationAmount);
    const [defaultUnit, setDefaultUnit] = useState<DurationUnit>(DURATION_PRESETS.LOW.defaultDurationUnit);
    const [maxAmount, setMaxAmount] = useState(DURATION_PRESETS.LOW.maxDurationAmount);
    const [maxUnit, setMaxUnit] = useState<DurationUnit>(DURATION_PRESETS.LOW.maxDurationUnit);
    const [allowPermanent, setAllowPermanent] = useState(false);
    const [autoApprove, setAutoApprove] = useState(false);
    const [roles, setRoles] = useState<SelectedPackageRole[]>([]);
    const [validationError, setValidationError] = useState<string>();
    const [saveError, setSaveError] = useState<unknown>();
    const [saving, setSaving] = useState(false);

    const selectRisk = (nextRisk: Entitlement["riskLevel"]) => {
        const preset = DURATION_PRESETS[nextRisk];
        setRiskLevel(nextRisk);
        setDefaultAmount(preset.defaultDurationAmount);
        setDefaultUnit(preset.defaultDurationUnit);
        setMaxAmount(preset.maxDurationAmount);
        setMaxUnit(preset.maxDurationUnit);
        setAllowPermanent(false);
        if (nextRisk !== "LOW") setAutoApprove(false);
        setValidationError(undefined);
    };

    const save = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (saving) {
            return;
        }
        if (roles.length === 0) {
            setValidationError(t("accessRequestsAdminPackageAtLeastOneRole"));
            return;
        }
        const defaultDurationSeconds = durationSeconds(defaultAmount, defaultUnit);
        const maxDurationSeconds = durationSeconds(maxAmount, maxUnit);
        if (defaultDurationSeconds === undefined || maxDurationSeconds === undefined
                || defaultDurationSeconds > maxDurationSeconds) {
            setValidationError(t("accessRequestsAdminInvalidDuration"));
            return;
        }
        setValidationError(undefined);
        setSaveError(undefined);
        setSaving(true);
        try {
            const created = await api.createAccessPackage({
                displayName, description, riskLevel, approverRoleId, defaultDurationSeconds,
                maxDurationSeconds, allowPermanent, autoApprove: riskLevel === "LOW" && autoApprove,
                roleMappings: roles.map(({ type, roleId }) => ({ type, roleId }))
            });
            onCreated(created);
        } catch (error) {
            setSaveError(error);
        } finally {
            setSaving(false);
        }
    };

    const error = saveError ? presentEntitlementsAdminError(saveError) : undefined;
    const errorMessage = error
        ? `${t(error.messageKey)}${error.requestId ? ` (${error.requestId})` : ""}`
        : undefined;

    return <Modal
        isOpen
        onClose={() => { if (!saving) onClose(); }}
        title={t("accessRequestsAdminPackageCreate")}
        variant={ModalVariant.large}
        actions={[
            <Button form="access-package-form" isLoading={saving} key="create" type="submit">
                {t("accessRequestsAdminPackageCreate")}
            </Button>,
            <Button isDisabled={saving} key="cancel" onClick={onClose} variant="link">
                {t("accessRequestsAdminCancel")}
            </Button>
        ]}
    >
        <Text component="p">{t("accessRequestsAdminPackageCreateDescription")}</Text>
        <Alert isInline variant="warning" title={t("accessRequestsAdminPackageAccessWarning")} className="pf-v5-u-my-md" />
        {validationError && <Alert isInline variant="danger" title={validationError} className="pf-v5-u-mb-md" />}
        {errorMessage && <Alert isInline variant="danger" title={errorMessage} className="pf-v5-u-mb-md" />}
        <Form id="access-package-form" onSubmit={(event) => void save(event)}>
            <FormGroup fieldId="access-package-display-name" isRequired label={t("accessRequestsAdminDisplayName")}>
                <TextInput id="access-package-display-name" isDisabled={saving} isRequired
                    onChange={(_event, value) => setDisplayName(value)} value={displayName} />
            </FormGroup>
            <FormGroup fieldId="access-package-description" isRequired label={t("accessRequestsAdminDescription")}>
                <TextArea id="access-package-description" isDisabled={saving} isRequired
                    onChange={(_event, value) => setDescription(value)} value={description} />
            </FormGroup>
            <FormGroup fieldId="access-package-risk" isRequired label={t("accessRequestsAdminRiskLevel")}>
                <FormSelect id="access-package-risk" isDisabled={saving} value={riskLevel}
                    onChange={(_event, value) => selectRisk(value as Entitlement["riskLevel"])}>
                    {(["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const).map((risk) =>
                        <FormSelectOption key={risk} label={t(`accessRequestsAdminRiskLevel${risk[0] + risk.slice(1).toLowerCase()}`)} value={risk} />)}
                </FormSelect>
            </FormGroup>
            <DurationField id="access-package-default-duration" label={t("accessRequestsAdminDefaultDuration")}
                amount={defaultAmount} unit={defaultUnit} disabled={saving}
                onAmount={setDefaultAmount} onUnit={setDefaultUnit} />
            <DurationField id="access-package-max-duration" label={t("accessRequestsAdminMaxDuration")}
                amount={maxAmount} unit={maxUnit} disabled={saving}
                onAmount={setMaxAmount} onUnit={setMaxUnit} />
            <FormGroup fieldId="access-package-permanent">
                <Checkbox id="access-package-permanent" isChecked={allowPermanent} isDisabled={saving}
                    label={t("accessRequestsAdminAllowPermanent")}
                    onChange={(_event, checked) => setAllowPermanent(checked)} />
            </FormGroup>
            <FormGroup fieldId="access-package-auto-approve">
                <Checkbox id="access-package-auto-approve" isChecked={autoApprove}
                    isDisabled={saving || riskLevel !== "LOW"}
                    label={t("accessRequestsAdminAutoApprove")}
                    onChange={(_event, checked) => setAutoApprove(checked)} />
                <Text component="small">{t("accessRequestsAdminAutoApproveHelp")}</Text>
            </FormGroup>
            <FormGroup fieldId="access-package-approver" isRequired label={t("accessRequestsAdminApproverRole")}>
                <KeycloakReferenceSelector api={api} fieldId="access-package-approver" isDisabled={saving}
                    onSelect={setApproverRoleId} resourceType="REALM_ROLE"
                    searchLabel="accessRequestsAdminSearchApproverRoles"
                    searchPlaceholder="accessRequestsAdminSearchApproverRolesPlaceholder"
                    selectionPlaceholder="accessRequestsAdminSelectApproverRole" value={approverRoleId} />
            </FormGroup>
            <AccessPackageRoleSelector api={api} disabled={saving} idPrefix="access-package"
                roles={roles} onChange={setRoles} />
        </Form>
    </Modal>;
}
