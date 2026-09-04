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
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type Entitlement,
    type EntitlementCreation
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";

type FormValues = EntitlementCreation & {
    requestable: boolean;
};

type DialogState = {
    entitlement?: Entitlement;
    mode: "create" | "edit";
};

const PAGE_SIZE_OPTIONS = [10, 20, 50].map((value) => ({ title: String(value), value }));

const emptyForm: FormValues = {
    approverRoleId: "",
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
    const [catalog, setCatalog] = useState<{ items: Entitlement[]; total: number }>();
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [initialLoading, setInitialLoading] = useState(true);
    const [refreshError, setRefreshError] = useState<unknown>();
    const [dialog, setDialog] = useState<DialogState>();
    const [form, setForm] = useState<FormValues>(emptyForm);
    const [formError, setFormError] = useState<unknown>();
    const [isSaving, setSaving] = useState(false);
    const [actionNotice, setActionNotice] = useState<string>();

    const load = useCallback(async () => {
        setRefreshError(undefined);
        try {
            const result = await api.list({ page, size });
            setCatalog({ items: result.items, total: result.total });
        } catch (error) {
            setRefreshError(error);
        } finally {
            setInitialLoading(false);
        }
    }, [api, page, size]);

    useEffect(() => {
        void load();
    }, [load]);

    const openCreate = () => {
        setActionNotice(undefined);
        setForm(emptyForm);
        setFormError(undefined);
        setDialog({ mode: "create" });
    };

    const openEdit = (entitlement: Entitlement) => {
        setActionNotice(undefined);
        setForm({
            approverRoleId: entitlement.approverRoleId,
            description: entitlement.description,
            displayName: entitlement.displayName,
            requestable: entitlement.requestable,
            resourceId: entitlement.resourceId,
            resourceType: entitlement.resourceType,
            riskLevel: entitlement.riskLevel
        });
        setFormError(undefined);
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

        setSaving(true);
        setFormError(undefined);
        try {
            if (dialog.mode === "create") {
                await api.create({
                    approverRoleId: form.approverRoleId,
                    description: form.description,
                    displayName: form.displayName,
                    resourceId: form.resourceId,
                    resourceType: form.resourceType,
                    riskLevel: form.riskLevel
                });
                setActionNotice(t("accessRequestsAdminCreated"));
            } else {
                await api.update(dialog.entitlement!.id, {
                    approverRoleId: form.approverRoleId,
                    description: form.description,
                    displayName: form.displayName,
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
    };

    const refreshMessage = refreshError ? errorText(refreshError, t) : undefined;
    const formMessage = formError ? errorText(formError, t) : undefined;

    return (
        <>
            <PageSection variant="light">
                <Title headingLevel="h1">{t("accessRequestsAdminCatalog")}</Title>
                <TextContent>
                    <Text component="p">{t("accessRequestsAdminCatalogDescription")}</Text>
                </TextContent>
            </PageSection>
            <PageSection>
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
                {initialLoading && !catalog ? (
                    <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
                ) : catalog?.items.length ? (
                    <DataList aria-label={t("accessRequestsAdminCatalog")}>
                        {catalog.items.map((entitlement) => (
                            <EntitlementListItem entitlement={entitlement} key={entitlement.id} onEdit={openEdit} />
                        ))}
                    </DataList>
                ) : (
                    <EmptyState>
                        <EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminEmpty")} />
                        <EmptyStateBody>{t("accessRequestsAdminCatalogDescription")}</EmptyStateBody>
                    </EmptyState>
                )}
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
                    error={formMessage}
                    form={form}
                    isSaving={isSaving}
                    mode={dialog.mode}
                    onClose={closeDialog}
                    onSave={save}
                    onUpdate={updateField}
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
                        </DescriptionList>
                    </DataListCell>,
                    <DataListCell key="state" width={1}>
                        <Label color={entitlement.requestable ? "green" : "grey"}>
                            {t(entitlement.requestable ? "accessRequestsAdminActive" : "accessRequestsAdminInactive")}
                        </Label>
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
    error,
    form,
    isSaving,
    mode,
    onClose,
    onSave,
    onUpdate
}: {
    error?: string;
    form: FormValues;
    isSaving: boolean;
    mode: "create" | "edit";
    onClose: () => void;
    onSave: (event: FormEvent<HTMLFormElement>) => Promise<void>;
    onUpdate: <Key extends keyof FormValues>(key: Key, value: FormValues[Key]) => void;
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
            <Form id="entitlement-form" onSubmit={(event) => void onSave(event)}>
                <FormGroup fieldId="entitlement-resource-type" isRequired label={t("accessRequestsAdminResourceType")}>
                    <FormSelect
                        id="entitlement-resource-type"
                        isDisabled={!isCreate || isSaving}
                        onChange={(_event, value) => onUpdate("resourceType", value as FormValues["resourceType"])}
                        value={form.resourceType}
                    >
                        <FormSelectOption label={t("accessRequestsAdminResourceTypeRealmRole")} value="REALM_ROLE" />
                        <FormSelectOption label={t("accessRequestsAdminResourceTypeClientRole")} value="CLIENT_ROLE" />
                        <FormSelectOption label={t("accessRequestsAdminResourceTypeGroup")} value="GROUP" />
                    </FormSelect>
                </FormGroup>
                <FormGroup fieldId="entitlement-resource-id" isRequired label={t("accessRequestsAdminResourceId")}>
                    <TextInput
                        id="entitlement-resource-id"
                        isDisabled={!isCreate || isSaving}
                        isRequired
                        onChange={(_event, value) => onUpdate("resourceId", value)}
                        value={form.resourceId}
                    />
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
                        onChange={(_event, value) => onUpdate("riskLevel", value as FormValues["riskLevel"])}
                        value={form.riskLevel}
                    >
                        <FormSelectOption label={t("accessRequestsAdminRiskLevelLow")} value="LOW" />
                        <FormSelectOption label={t("accessRequestsAdminRiskLevelMedium")} value="MEDIUM" />
                        <FormSelectOption label={t("accessRequestsAdminRiskLevelHigh")} value="HIGH" />
                        <FormSelectOption label={t("accessRequestsAdminRiskLevelCritical")} value="CRITICAL" />
                    </FormSelect>
                </FormGroup>
                <FormGroup fieldId="entitlement-approver-role" isRequired label={t("accessRequestsAdminApproverRole")}>
                    <TextInput
                        id="entitlement-approver-role"
                        isDisabled={isSaving}
                        isRequired
                        onChange={(_event, value) => onUpdate("approverRoleId", value)}
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
