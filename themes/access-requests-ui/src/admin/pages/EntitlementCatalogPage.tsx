import {
    Alert,
    Button,
    ButtonVariant,
    Checkbox,
    DataList,
    DataListAction,
    DataListCell,
    DataListItem,
    DataListItemCells,
    DataListItemRow,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    EmptyState,
    EmptyStateBody,
    EmptyStateHeader,
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
    Label,
    Modal,
    ModalVariant,
    PageSection,
    Pagination,
    Spinner,
    Text,
    TextArea,
    TextContent,
    TextInput,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
    Title
} from "@patternfly/react-core";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type Entitlement,
    type EntitlementCreation,
    type EntitlementsAdminApi,
    type KeycloakReference
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";
import { AccessRequestsAdminTabs } from "./AccessRequestsAdminTabs";

type DurationUnit = "SECONDS" | "HOURS" | "DAYS";

type FormValues = Omit<EntitlementCreation, "defaultDurationSeconds" | "maxDurationSeconds" | "allowPermanent"> & {
    requestable: boolean;
    defaultDurationAmount: string;
    defaultDurationUnit: DurationUnit;
    maxDurationAmount: string;
    maxDurationUnit: DurationUnit;
    allowPermanent: boolean;
};

type DialogState = {
    entitlement?: Entitlement;
    mode: "create" | "edit";
};

const PAGE_SIZE_OPTIONS = [10, 20, 50].map((value) => ({ title: String(value), value }));
const DURATION_UNIT_SECONDS: Record<DurationUnit, number> = { SECONDS: 1, HOURS: 3_600, DAYS: 86_400 };
const DURATION_PRESETS: Record<Entitlement["riskLevel"], Pick<FormValues,
    "defaultDurationAmount" | "defaultDurationUnit" | "maxDurationAmount" | "maxDurationUnit">> = {
    LOW: { defaultDurationAmount: "30", defaultDurationUnit: "DAYS", maxDurationAmount: "90", maxDurationUnit: "DAYS" },
    MEDIUM: { defaultDurationAmount: "7", defaultDurationUnit: "DAYS", maxDurationAmount: "30", maxDurationUnit: "DAYS" },
    HIGH: { defaultDurationAmount: "8", defaultDurationUnit: "HOURS", maxDurationAmount: "24", maxDurationUnit: "HOURS" },
    CRITICAL: { defaultDurationAmount: "1", defaultDurationUnit: "HOURS", maxDurationAmount: "4", maxDurationUnit: "HOURS" }
};

const emptyForm: FormValues = {
    approverRoleId: "",
    allowPermanent: false,
    ...DURATION_PRESETS.LOW,
    description: "",
    displayName: "",
    requestable: false,
    resourceId: "",
    resourceType: "REALM_ROLE",
    riskLevel: "LOW"
};

export function EntitlementCatalogPage() {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [catalog, setCatalog] = useState<{ items: Entitlement[]; total: number; page: number; size: number }>();
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [loading, setLoading] = useState(true);
    const [refreshError, setRefreshError] = useState<unknown>();
    const [dialog, setDialog] = useState<DialogState>();
    const [form, setForm] = useState<FormValues>(emptyForm);
    const [formError, setFormError] = useState<unknown>();
    const [durationError, setDurationError] = useState(false);
    const [isSaving, setSaving] = useState(false);
    const [actionNotice, setActionNotice] = useState<string>();
    const latestLoad = useRef(0);

    const load = useCallback(async () => {
        const loadId = ++latestLoad.current;
        setLoading(true);
        setRefreshError(undefined);
        try {
            const result = await api.list({ page, size });
            if (loadId === latestLoad.current) {
                setCatalog({ items: result.items, total: result.total, page, size });
            }
        } catch (error) {
            if (loadId === latestLoad.current) {
                setRefreshError(error);
            }
        } finally {
            if (loadId === latestLoad.current) {
                setLoading(false);
            }
        }
    }, [api, page, size]);

    useEffect(() => {
        void load();
        return () => {
            latestLoad.current++;
        };
    }, [load]);

    const openCreate = () => {
        setActionNotice(undefined);
        setForm(emptyForm);
        setFormError(undefined);
        setDurationError(false);
        setDialog({ mode: "create" });
    };

    const openEdit = (entitlement: Entitlement) => {
        setActionNotice(undefined);
        setForm({
            approverRoleId: entitlement.approverRoleId,
            allowPermanent: entitlement.allowPermanent,
            defaultDurationAmount: durationInput(entitlement.defaultDurationSeconds).amount,
            defaultDurationUnit: durationInput(entitlement.defaultDurationSeconds).unit,
            description: entitlement.description,
            displayName: entitlement.displayName,
            requestable: entitlement.requestable,
            maxDurationAmount: durationInput(entitlement.maxDurationSeconds).amount,
            maxDurationUnit: durationInput(entitlement.maxDurationSeconds).unit,
            resourceId: entitlement.resourceId,
            resourceType: entitlement.resourceType,
            riskLevel: entitlement.riskLevel
        });
        setFormError(undefined);
        setDurationError(false);
        setDialog({ entitlement, mode: "edit" });
    };

    const closeDialog = () => {
        if (!isSaving) {
            setDialog(undefined);
            setFormError(undefined);
        }
    };

    const save = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!dialog) {
            return;
        }

        const defaultDurationSeconds = durationSeconds(form.defaultDurationAmount, form.defaultDurationUnit);
        const maxDurationSeconds = durationSeconds(form.maxDurationAmount, form.maxDurationUnit);
        if (defaultDurationSeconds === undefined || maxDurationSeconds === undefined
                || defaultDurationSeconds > maxDurationSeconds) {
            setDurationError(true);
            return;
        }

        setSaving(true);
        setFormError(undefined);
        setDurationError(false);
        try {
            if (dialog.mode === "create") {
                await api.create({
                    approverRoleId: form.approverRoleId,
                    allowPermanent: form.allowPermanent,
                    defaultDurationSeconds,
                    description: form.description,
                    displayName: form.displayName,
                    maxDurationSeconds,
                    resourceId: form.resourceId,
                    resourceType: form.resourceType,
                    riskLevel: form.riskLevel
                });
                setActionNotice(t("accessRequestsAdminCreated"));
            } else {
                await api.update(dialog.entitlement!.id, {
                    approverRoleId: form.approverRoleId,
                    allowPermanent: form.allowPermanent,
                    defaultDurationSeconds,
                    description: form.description,
                    displayName: form.displayName,
                    maxDurationSeconds,
                    requestable: form.requestable,
                    riskLevel: form.riskLevel,
                    version: dialog.entitlement!.version
                });
                setActionNotice(t("accessRequestsAdminUpdated"));
            }
            setDialog(undefined);
            await load();
        } catch (error) {
            setFormError(error);
        } finally {
            setSaving(false);
        }
    };

    const updateField = <Key extends keyof FormValues>(key: Key, value: FormValues[Key]) => {
        setForm((current) => ({ ...current, [key]: value }));
        setDurationError(false);
    };

    const updateRisk = (riskLevel: FormValues["riskLevel"]) => {
        setForm((current) => ({ ...current, riskLevel, ...DURATION_PRESETS[riskLevel], allowPermanent: false }));
        setDurationError(false);
    };

    const refreshMessage = refreshError ? errorText(refreshError, t) : undefined;
    const formMessage = formError ? errorText(formError, t) : undefined;
    const visibleCatalog = catalog?.page === page && catalog.size === size ? catalog : undefined;

    return (
        <>
            <PageSection variant="light">
                <Title headingLevel="h1">{t("accessRequestsAdminCatalog")}</Title>
                <TextContent>
                    <Text component="p">{t("accessRequestsAdminCatalogDescription")}</Text>
                </TextContent>
            </PageSection>
            <PageSection>
                <AccessRequestsAdminTabs active="catalog" />
                {actionNotice && (
                    <Alert isInline title={actionNotice} variant="success" className="pf-v5-u-mb-lg" />
                )}
                {refreshMessage && (
                    <Alert
                        actionClose={<Button variant={ButtonVariant.plain} aria-label={t("close")} onClick={() => setRefreshError(undefined)} />}
                        isInline
                        title={refreshMessage}
                        variant="danger"
                        className="pf-v5-u-mb-lg"
                    />
                )}
                <Toolbar aria-label={t("accessRequestsAdminCatalog")}>
                    <ToolbarContent>
                        <ToolbarItem>
                            <Button onClick={openCreate} type="button">
                                {t("accessRequestsAdminCreateEntitlement")}
                            </Button>
                        </ToolbarItem>
                        {catalog && catalog.total > 0 && (
                            <ToolbarItem align={{ default: "alignRight" }} variant="pagination">
                                <CatalogPagination
                                    page={page}
                                    size={size}
                                    total={catalog.total}
                                    onPageChange={setPage}
                                    onSizeChange={(nextSize) => {
                                        setPage(0);
                                        setSize(nextSize);
                                    }}
                                />
                            </ToolbarItem>
                        )}
                    </ToolbarContent>
                </Toolbar>
                {!visibleCatalog && (loading || !refreshError) ? (
                    <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
                ) : visibleCatalog?.items.length ? (
                    <DataList aria-label={t("accessRequestsAdminCatalog")}>
                        {visibleCatalog.items.map((entitlement) => (
                            <EntitlementListItem entitlement={entitlement} key={entitlement.id} onEdit={openEdit} />
                        ))}
                    </DataList>
                ) : visibleCatalog ? (
                    <EmptyState>
                        <EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminEmpty")} />
                        <EmptyStateBody>{t("accessRequestsAdminCatalogDescription")}</EmptyStateBody>
                    </EmptyState>
                ) : null}
                {catalog && catalog.total > 0 && (
                    <CatalogPagination
                        page={page}
                        size={size}
                        total={catalog.total}
                        onPageChange={setPage}
                        onSizeChange={(nextSize) => {
                            setPage(0);
                            setSize(nextSize);
                        }}
                        variant="bottom"
                    />
                )}
            </PageSection>
            {dialog && (
                <EntitlementDialog
                    api={api}
                    error={formMessage}
                    durationError={durationError}
                    form={form}
                    isSaving={isSaving}
                    mode={dialog.mode}
                    onClose={closeDialog}
                    onSave={save}
                    onUpdate={updateField}
                    onRiskChange={updateRisk}
                />
            )}
        </>
    );
}

function EntitlementListItem({ entitlement, onEdit }: { entitlement: Entitlement; onEdit: (entitlement: Entitlement) => void }) {
    const { t } = useTranslation();
    const titleId = `entitlement-${entitlement.id}`;
    return (
        <DataListItem aria-labelledby={titleId}>
            <DataListItemRow>
                <DataListItemCells dataListCells={[
                    <DataListCell key="name" width={3}>
                        <Title headingLevel="h2" id={titleId} size="md">{entitlement.displayName}</Title>
                        <Text component="small">{t(resourceTypeKey(entitlement.resourceType))}: {entitlement.resourceId}</Text>
                        <Text component="p">{entitlement.description}</Text>
                    </DataListCell>,
                    <DataListCell key="configuration" width={3}>
                        <DescriptionList isCompact isHorizontal>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminRiskLevel")}</DescriptionListTerm>
                                <DescriptionListDescription><RiskLabel level={entitlement.riskLevel} /></DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminApproverRole")}</DescriptionListTerm>
                                <DescriptionListDescription>{entitlement.approverRoleId}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminDefaultDuration")}</DescriptionListTerm>
                                <DescriptionListDescription>{durationText(entitlement.defaultDurationSeconds, t)}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminMaxDuration")}</DescriptionListTerm>
                                <DescriptionListDescription>{durationText(entitlement.maxDurationSeconds, t)}</DescriptionListDescription>
                            </DescriptionListGroup>
                        </DescriptionList>
                    </DataListCell>,
                    <DataListCell key="state" width={1}>
                        <Label color={entitlement.requestable ? "green" : "grey"}>
                            {t(entitlement.requestable
                                ? "accessRequestsAdminOpenToRequests"
                                : "accessRequestsAdminClosedToRequests")}
                        </Label>
                        <Text component="small">{t(entitlement.allowPermanent
                            ? "accessRequestsAdminPermanentAllowed" : "accessRequestsAdminTemporaryOnly")}</Text>
                    </DataListCell>
                ]} />
                <DataListAction aria-labelledby={titleId} id={`entitlement-actions-${entitlement.id}`} aria-label={t("accessRequestsAdminEditEntitlement")}>
                    <Button variant="secondary" onClick={() => onEdit(entitlement)} type="button">
                        {t("accessRequestsAdminEditEntitlement")}
                    </Button>
                </DataListAction>
            </DataListItemRow>
        </DataListItem>
    );
}

function EntitlementDialog({
    api,
    error,
    durationError,
    form,
    isSaving,
    mode,
    onClose,
    onSave,
    onUpdate,
    onRiskChange
}: {
    api: EntitlementsAdminApi;
    error?: string;
    durationError: boolean;
    form: FormValues;
    isSaving: boolean;
    mode: "create" | "edit";
    onClose: () => void;
    onSave: (event: FormEvent<HTMLFormElement>) => Promise<void>;
    onUpdate: <Key extends keyof FormValues>(key: Key, value: FormValues[Key]) => void;
    onRiskChange: (riskLevel: FormValues["riskLevel"]) => void;
}) {
    const { t } = useTranslation();
    const isCreate = mode === "create";
    const modalTitle = t(isCreate ? "accessRequestsAdminCreateEntitlement" : "accessRequestsAdminEditEntitlement");

    return (
        <Modal
            aria-label={modalTitle}
            isOpen
            onClose={onClose}
            title={modalTitle}
            variant={ModalVariant.medium}
            actions={[
                <Button form="entitlement-form" isLoading={isSaving} key="save" type="submit">
                    {t("accessRequestsAdminSave")}
                </Button>,
                <Button isDisabled={isSaving} key="cancel" onClick={onClose} variant="link">
                    {t("accessRequestsAdminCancel")}
                </Button>
            ]}
        >
            {error && <Alert isInline title={error} variant="danger" className="pf-v5-u-mb-lg" />}
            {durationError && <Alert isInline title={t("accessRequestsAdminInvalidDuration")} variant="danger" className="pf-v5-u-mb-lg" />}
            <Form id="entitlement-form" onSubmit={(event) => void onSave(event)}>
                <FormGroup fieldId="entitlement-resource-type" isRequired label={t("accessRequestsAdminResourceType")}>
                    <FormSelect
                        id="entitlement-resource-type"
                        isDisabled={!isCreate || isSaving}
                        onChange={(_event, value) => {
                            onUpdate("resourceType", value as FormValues["resourceType"]);
                            onUpdate("resourceId", "");
                        }}
                        value={form.resourceType}
                    >
                        <FormSelectOption label={t("accessRequestsAdminResourceTypeRealmRole")} value="REALM_ROLE" />
                        <FormSelectOption label={t("accessRequestsAdminResourceTypeClientRole")} value="CLIENT_ROLE" />
                        <FormSelectOption label={t("accessRequestsAdminResourceTypeGroup")} value="GROUP" />
                    </FormSelect>
                </FormGroup>
                {isCreate && form.resourceType === "GROUP" && <Alert
                    isInline
                    variant="warning"
                    title={t("accessRequestsAdminGroupAccessWarning")}
                />}
                <FormGroup fieldId="entitlement-resource-id" isRequired label={t("accessRequestsAdminResourceId")}>
                    {isCreate ? (
                        <KeycloakReferenceSelector
                            api={api}
                            fieldId="entitlement-resource-id"
                            isDisabled={isSaving}
                            key={form.resourceType}
                            onSelect={(value) => onUpdate("resourceId", value)}
                            resourceType={form.resourceType}
                            searchLabel="accessRequestsAdminSearchResources"
                            searchPlaceholder="accessRequestsAdminSearchResourcesPlaceholder"
                            selectionPlaceholder="accessRequestsAdminSelectResource"
                            value={form.resourceId}
                        />
                    ) : (
                        <TextInput id="entitlement-resource-id" readOnly value={form.resourceId} />
                    )}
                </FormGroup>
                <FormGroup fieldId="entitlement-display-name" isRequired label={t("accessRequestsAdminDisplayName")}>
                    <TextInput
                        id="entitlement-display-name"
                        isDisabled={isSaving}
                        isRequired
                        onChange={(_event, value) => onUpdate("displayName", value)}
                        value={form.displayName}
                    />
                </FormGroup>
                <FormGroup fieldId="entitlement-description" isRequired label={t("accessRequestsAdminDescription")}>
                    <TextArea
                        id="entitlement-description"
                        isDisabled={isSaving}
                        isRequired
                        onChange={(_event, value) => onUpdate("description", value)}
                        value={form.description}
                    />
                </FormGroup>
                <FormGroup fieldId="entitlement-risk-level" isRequired label={t("accessRequestsAdminRiskLevel")}>
                    <FormSelect
                        id="entitlement-risk-level"
                        isDisabled={isSaving}
                        onChange={(_event, value) => onRiskChange(value as FormValues["riskLevel"])}
                        value={form.riskLevel}
                    >
                        <FormSelectOption label={t("accessRequestsAdminRiskLevelLow")} value="LOW" />
                        <FormSelectOption label={t("accessRequestsAdminRiskLevelMedium")} value="MEDIUM" />
                        <FormSelectOption label={t("accessRequestsAdminRiskLevelHigh")} value="HIGH" />
                        <FormSelectOption label={t("accessRequestsAdminRiskLevelCritical")} value="CRITICAL" />
                    </FormSelect>
                </FormGroup>
                <DurationFormField
                    amount={form.defaultDurationAmount}
                    amountKey="defaultDurationAmount"
                    isSaving={isSaving}
                    label={t("accessRequestsAdminDefaultDuration")}
                    onUpdate={onUpdate}
                    unit={form.defaultDurationUnit}
                    unitKey="defaultDurationUnit"
                />
                <DurationFormField
                    amount={form.maxDurationAmount}
                    amountKey="maxDurationAmount"
                    isSaving={isSaving}
                    label={t("accessRequestsAdminMaxDuration")}
                    onUpdate={onUpdate}
                    unit={form.maxDurationUnit}
                    unitKey="maxDurationUnit"
                />
                <FormGroup fieldId="entitlement-allow-permanent">
                    <Checkbox
                        id="entitlement-allow-permanent"
                        isChecked={form.allowPermanent}
                        isDisabled={isSaving}
                        label={t("accessRequestsAdminAllowPermanent")}
                        onChange={(_event, checked) => onUpdate("allowPermanent", checked)}
                    />
                </FormGroup>
                <FormGroup fieldId="entitlement-approver-role" isRequired label={t("accessRequestsAdminApproverRole")}>
                    <KeycloakReferenceSelector
                        api={api}
                        fieldId="entitlement-approver-role"
                        isDisabled={isSaving}
                        onSelect={(value) => onUpdate("approverRoleId", value)}
                        resourceType="REALM_ROLE"
                        searchLabel="accessRequestsAdminSearchApproverRoles"
                        searchPlaceholder="accessRequestsAdminSearchApproverRolesPlaceholder"
                        selectionPlaceholder="accessRequestsAdminSelectApproverRole"
                        value={form.approverRoleId}
                    />
                </FormGroup>
                {!isCreate && (
                    <FormGroup fieldId="entitlement-requestable">
                        <Checkbox
                            id="entitlement-requestable"
                            isChecked={form.requestable}
                            isDisabled={isSaving}
                            label={t("accessRequestsAdminRequestable")}
                            onChange={(_event, checked) => onUpdate("requestable", checked)}
                        />
                    </FormGroup>
                )}
            </Form>
        </Modal>
    );
}

function DurationFormField({ amount, amountKey, isSaving, label, onUpdate, unit, unitKey }: {
    amount: string;
    amountKey: "defaultDurationAmount" | "maxDurationAmount";
    isSaving: boolean;
    label: string;
    onUpdate: <Key extends keyof FormValues>(key: Key, value: FormValues[Key]) => void;
    unit: DurationUnit;
    unitKey: "defaultDurationUnit" | "maxDurationUnit";
}) {
    const { t } = useTranslation();
    return <>
        <FormGroup fieldId={amountKey} isRequired label={label}>
            <TextInput
                id={amountKey}
                isDisabled={isSaving}
                isRequired
                min={1}
                onChange={(_event, value) => onUpdate(amountKey, value)}
                step={1}
                type="number"
                value={amount}
            />
        </FormGroup>
        <FormGroup fieldId={unitKey} label={t("accessRequestsAdminDurationUnit")}>
            <FormSelect
                id={unitKey}
                isDisabled={isSaving}
                onChange={(_event, value) => onUpdate(unitKey, value as DurationUnit)}
                value={unit}
            >
                <FormSelectOption label={t("accessRequestsAdminDurationUnitSeconds")} value="SECONDS" />
                <FormSelectOption label={t("accessRequestsAdminDurationUnitHours")} value="HOURS" />
                <FormSelectOption label={t("accessRequestsAdminDurationUnitDays")} value="DAYS" />
            </FormSelect>
        </FormGroup>
    </>;
}

function durationInput(seconds: number): { amount: string; unit: DurationUnit } {
    if (seconds % DURATION_UNIT_SECONDS.DAYS === 0) {
        return { amount: String(seconds / DURATION_UNIT_SECONDS.DAYS), unit: "DAYS" };
    }
    if (seconds % DURATION_UNIT_SECONDS.HOURS === 0) {
        return { amount: String(seconds / DURATION_UNIT_SECONDS.HOURS), unit: "HOURS" };
    }
    return { amount: String(seconds), unit: "SECONDS" };
}

function durationSeconds(amount: string, unit: DurationUnit): number | undefined {
    const parsed = Number(amount);
    const seconds = parsed * DURATION_UNIT_SECONDS[unit];
    return Number.isSafeInteger(parsed) && parsed > 0 && Number.isSafeInteger(seconds)
        ? seconds : undefined;
}

function durationText(seconds: number, t: (key: string) => string): string {
    const input = durationInput(seconds);
    const unitKey = {
        SECONDS: "accessRequestsAdminDurationUnitSeconds",
        HOURS: "accessRequestsAdminDurationUnitHours",
        DAYS: "accessRequestsAdminDurationUnitDays"
    }[input.unit];
    return `${input.amount} ${t(unitKey)}`;
}

function KeycloakReferenceSelector({
    api,
    fieldId,
    isDisabled,
    onSelect,
    resourceType,
    searchLabel,
    searchPlaceholder,
    selectionPlaceholder,
    value
}: {
    api: EntitlementsAdminApi;
    fieldId: string;
    isDisabled: boolean;
    onSelect: (value: string) => void;
    resourceType: Entitlement["resourceType"];
    searchLabel: string;
    searchPlaceholder: string;
    selectionPlaceholder: string;
    value: string;
}) {
    const { t } = useTranslation();
    const [search, setSearch] = useState("");
    const [first, setFirst] = useState(0);
    const [retryNonce, setRetryNonce] = useState(0);
    const [nextFirst, setNextFirst] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [references, setReferences] = useState<KeycloakReference[]>([]);
    const [rememberedSelection, setRememberedSelection] = useState<KeycloakReference>();
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<unknown>();

    useEffect(() => {
        const term = search.trim();
        if (term.length < 2 && !value) {
            setReferences([]);
            setHasMore(false);
            setNextFirst(0);
            setLoading(false);
            setLoadError(undefined);
            return;
        }
        let active = true;
        const controller = new AbortController();
        setLoading(true);
        setLoadError(undefined);
        if (first === 0) {
            setReferences([]);
            setHasMore(false);
        }
        const timeout = window.setTimeout(() => {
            void api.references(resourceType, {
                search: term.length >= 2 ? term : undefined,
                selectedId: first === 0 && term.length < 2 ? value || undefined : undefined,
                first,
                max: 50,
                signal: controller.signal
            })
                .then((page) => {
                    if (active) {
                        setReferences((current) => first === 0 ? page.items : [
                            ...current,
                            ...page.items.filter((item) => !current.some((existing) => existing.id === item.id))
                        ]);
                        const selected = page.items.find((item) => item.id === value);
                        if (selected) {
                            setRememberedSelection(selected);
                        }
                        setNextFirst(page.nextFirst);
                        setHasMore(page.hasMore);
                    }
                })
                .catch((referenceError: unknown) => {
                    if (active) {
                        if (first === 0) {
                            setReferences([]);
                        }
                        setLoadError(referenceError);
                    }
                })
                .finally(() => {
                    if (active) {
                        setLoading(false);
                    }
                });
        }, first === 0 ? 300 : 0);
        return () => {
            active = false;
            window.clearTimeout(timeout);
            controller.abort();
        };
    }, [api, first, retryNonce, search, resourceType, value]);

    const selectedReference = references.find((reference) => reference.id === value);
    const selectedOption = selectedReference ?? (rememberedSelection?.id === value ? rememberedSelection : undefined) ?? (value
        ? { description: "", id: value, name: value, type: resourceType }
        : undefined);

    return (
        <>
            <TextInput
                aria-label={t(searchLabel)}
                id={`${fieldId}-search`}
                isDisabled={isDisabled}
                onChange={(_event, nextSearch) => {
                    setSearch(nextSearch);
                    setFirst(0);
                }}
                placeholder={t(searchPlaceholder)}
                value={search}
            />
            {loadError && <Alert isInline title={errorText(loadError, t)} variant="danger" className="pf-v5-u-mt-sm" />}
            <FormSelect
                aria-label={t(selectionPlaceholder)}
                id={fieldId}
                isDisabled={isDisabled || loading || (first === 0 && Boolean(loadError))}
                isRequired
                onChange={(_event, nextValue) => {
                    setRememberedSelection(references.find((reference) => reference.id === nextValue));
                    setFirst(0);
                    onSelect(nextValue);
                }}
                value={value}
            >
                <FormSelectOption isDisabled label={t(selectionPlaceholder)} value="" />
                {selectedOption && <FormSelectOption label={selectedOption.name} value={selectedOption.id} />}
                {loading && <FormSelectOption isDisabled label={t("accessRequestsAdminReferencesLoading")} value="loading" />}
                {!loading && !loadError && search.trim().length < 2 && (
                    <FormSelectOption isDisabled label={t("accessRequestsAdminReferencesEnterSearch")} value="enter-search" />
                )}
                {!loading && !loadError && search.trim().length >= 2 && references.length === 0 && (
                    <FormSelectOption isDisabled label={t("accessRequestsAdminReferencesEmpty")} value="empty" />
                )}
                {references.filter((reference) => reference.id !== selectedOption?.id).map((reference) => (
                    <FormSelectOption key={reference.id} label={reference.name} value={reference.id} />
                ))}
            </FormSelect>
            {hasMore && <Button
                isDisabled={isDisabled || loading}
                onClick={() => first === nextFirst
                    ? setRetryNonce((current) => current + 1)
                    : setFirst(nextFirst)}
                variant="link"
            >
                {t("accessRequestsAdminReferencesLoadMore")}
            </Button>}
        </>
    );
}

function CatalogPagination({
    onPageChange,
    onSizeChange,
    page,
    size,
    total,
    variant = "top"
}: {
    onPageChange: (page: number) => void;
    onSizeChange: (size: number) => void;
    page: number;
    size: number;
    total: number;
    variant?: "top" | "bottom";
}) {
    return (
        <Pagination
            itemCount={total}
            onPerPageSelect={(_event, nextSize) => onSizeChange(nextSize)}
            onSetPage={(_event, nextPage) => onPageChange(nextPage - 1)}
            page={page + 1}
            perPage={size}
            perPageOptions={PAGE_SIZE_OPTIONS}
            variant={variant}
            widgetId="access-request-entitlements"
        />
    );
}

function RiskLabel({ level }: { level: Entitlement["riskLevel"] }) {
    const { t } = useTranslation();
    const colors = {
        LOW: "green",
        MEDIUM: "orange",
        HIGH: "red",
        CRITICAL: "purple"
    } as const;
    return <Label color={colors[level]}>{t(riskLevelKey(level))}</Label>;
}

function resourceTypeKey(type: Entitlement["resourceType"]) {
    return {
        CLIENT_ROLE: "accessRequestsAdminResourceTypeClientRole",
        GROUP: "accessRequestsAdminResourceTypeGroup",
        REALM_ROLE: "accessRequestsAdminResourceTypeRealmRole"
    }[type];
}

function riskLevelKey(level: Entitlement["riskLevel"]) {
    return {
        CRITICAL: "accessRequestsAdminRiskLevelCritical",
        HIGH: "accessRequestsAdminRiskLevelHigh",
        LOW: "accessRequestsAdminRiskLevelLow",
        MEDIUM: "accessRequestsAdminRiskLevelMedium"
    }[level];
}

function errorText(error: unknown, translate: (key: string) => string) {
    const presentation = presentEntitlementsAdminError(error);
    const message = translate(presentation.messageKey);
    return presentation.requestId ? `${message} (${presentation.requestId})` : message;
}
