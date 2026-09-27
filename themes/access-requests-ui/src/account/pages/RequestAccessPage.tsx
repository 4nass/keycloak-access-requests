import { Page } from "@keycloak/keycloak-account-ui";
import {
    ActionGroup,
    Button,
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
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
    Modal,
    SearchInput,
    TextArea,
    TextInput
} from "@patternfly/react-core";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import { AccessRequestEmptyState } from "./AccessRequestEmptyState";
import { AccessRequestPagination, type AccessRequestPaginationState } from "./AccessRequestPagination";
import { RiskLevelLabel, resourceTypeLabel } from "./AccessRequestPresentation";
import { useAccessRequestAlerts } from "./useAccessRequestAlerts";

export type RequestableEntitlement = {
    id: string;
    name: string;
    description: string;
    resourceType: string;
    riskLevel: string;
    alreadyGranted: boolean;
    pendingRequest: boolean;
    defaultDurationSeconds?: number;
    maxDurationSeconds?: number;
    allowPermanent?: boolean;
};

type AccessRequestSubmission = {
    entitlementId: string;
    justification: string;
    durationSeconds?: number | null;
    permanent?: boolean;
};

type RequestAccessPageProps = {
    entries: RequestableEntitlement[];
    onRequest: (submission: AccessRequestSubmission) => void | Promise<void>;
    pagination?: AccessRequestPaginationState;
    onRefresh?: () => void | Promise<void>;
    search?: {
        value: string;
        onChange: (value: string) => void;
    };
};

export function RequestAccessPage({ entries, onRequest, onRefresh, pagination, search }: RequestAccessPageProps) {
    const { t } = useTranslation();
    const { addAlert, addError } = useAccessRequestAlerts();
    const [selectedEntry, setSelectedEntry] = useState<RequestableEntitlement>();
    const [justification, setJustification] = useState("");
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [durationAmount, setDurationAmount] = useState("");
    const [durationUnit, setDurationUnit] = useState<DurationUnit>("HOURS");
    const [permanent, setPermanent] = useState(false);

    const closeDialog = () => {
        setSelectedEntry(undefined);
        setJustification("");
        setDurationAmount("");
        setPermanent(false);
    };

    const dismissDialog = () => {
        if (!isSubmitting) {
            closeDialog();
        }
    };

    const submit = async () => {
        const durationSeconds = selectedEntry && !permanent
            ? selectedDurationSeconds(durationAmount, durationUnit) : undefined;
        if (!selectedEntry || !justification.trim() || isSubmitting
                || (!permanent && (durationSeconds === undefined
                    || durationSeconds > maximumDuration(selectedEntry)))
                || (permanent && !selectedEntry.allowPermanent)) {
            return;
        }

        setIsSubmitting(true);

        try {
            await onRequest({
                entitlementId: selectedEntry.id,
                justification: justification.trim(),
                durationSeconds: permanent ? null : durationSeconds,
                permanent
            });
        } catch (error) {
            addError(error);
            setIsSubmitting(false);
            return;
        }

        addAlert(t("accessRequestsRequestSubmitted"));
        closeDialog();
        try {
            await onRefresh?.();
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <Page
            description={t("accessRequestsRequestAccessDescription")}
            title={t("accessRequestsRequestAccess")}
        >
            <>
                <AccessRequestPagination pagination={pagination}>
                    {search && (
                        <SearchInput
                            aria-label={t("accessRequestsSearchCatalog")}
                            onChange={(_, value) => search.onChange(value)}
                            onClear={() => search.onChange("")}
                            onSearch={(_, value) => search.onChange(value)}
                            placeholder={t("accessRequestsSearchCatalogPlaceholder")}
                            resetButtonLabel={t("accessRequestsClearSearch")}
                            searchInputId="access-request-catalog-search"
                            submitSearchButtonLabel={t("accessRequestsSearchCatalog")}
                            value={search.value}
                        />
                    )}
                </AccessRequestPagination>
                {entries.length === 0 ? (
                    <AccessRequestEmptyState
                        description={t("accessRequestsNoRequestableAccessDescription")}
                        title={t("accessRequestsNoRequestableAccess")}
                    />
                ) : (
                    <DataList aria-label={t("accessRequestsRequestAccess")}>
                        {entries.map((entry) => {
                            const titleId = `requestable-entitlement-${entry.id}-title`;
                            return (
                                <DataListItem aria-labelledby={titleId} id={`requestable-entitlement-${entry.id}`} key={entry.id}>
                                    <DataListItemRow>
                                        <DataListItemCells
                                            dataListCells={[
                                                <DataListCell key="details" width={3}>
                                                    <strong id={titleId}>{entry.name}</strong>
                                                    <p>{entry.description}</p>
                                                </DataListCell>,
                                                <DataListCell key="attributes" width={2}>
                                                    <DescriptionList isCompact>
                                                        <DescriptionListGroup>
                                                            <DescriptionListTerm>{t("accessRequestsResourceType")}</DescriptionListTerm>
                                                            <DescriptionListDescription>
                                                                {resourceTypeLabel(entry.resourceType, t)}
                                                            </DescriptionListDescription>
                                                        </DescriptionListGroup>
                                                        <DescriptionListGroup>
                                                            <DescriptionListTerm>{t("accessRequestsRiskLabel")}</DescriptionListTerm>
                                                            <DescriptionListDescription>
                                                                <RiskLevelLabel riskLevel={entry.riskLevel} t={t} />
                                                            </DescriptionListDescription>
                                                        </DescriptionListGroup>
                                                    </DescriptionList>
                                                </DataListCell>
                                            ]}
                                        />
                                        <DataListAction
                                            aria-label={t("accessRequestsRequestAccessTo", { entitlement: entry.name })}
                                            aria-labelledby={titleId}
                                            id={`requestable-entitlement-${entry.id}-action`}
                                        >
                                            {entry.alreadyGranted ? (
                                                <p>{t("accessRequestsAlreadyGranted")}</p>
                                            ) : entry.pendingRequest ? (
                                                <p>{t("accessRequestsRequestPending")}</p>
                                            ) : (
                                                <Button type="button" variant="primary" onClick={() => {
                                                    const initial = durationInput(defaultDuration(entry));
                                                    setDurationAmount(initial.amount);
                                                    setDurationUnit(initial.unit);
                                                    setPermanent(false);
                                                    setSelectedEntry(entry);
                                                }}>
                                                    {t("accessRequestsRequestAccess")}
                                                </Button>
                                            )}
                                        </DataListAction>
                                    </DataListItemRow>
                                </DataListItem>
                            );
                        })}
                    </DataList>
                )}
            {selectedEntry && (
                <Modal
                    elementToFocus="#access-request-justification"
                    isOpen
                    onClose={dismissDialog}
                    showClose={!isSubmitting}
                    title={t("accessRequestsRequestAccessTo", { entitlement: selectedEntry.name })}
                    variant="small"
                >
                    <Form
                        onSubmit={(event) => {
                            event.preventDefault();
                            void submit();
                        }}
                    >
                        <FormGroup fieldId="access-request-justification" isRequired label={t("accessRequestsJustification")}>
                            <TextArea
                                aria-label={t("accessRequestsJustification")}
                                id="access-request-justification"
                                isDisabled={isSubmitting}
                                isRequired
                                onChange={(_, value) => setJustification(value)}
                                value={justification}
                            />
                        </FormGroup>
                        <FormGroup fieldId="access-request-duration" isRequired={!permanent}
                            label={t("accessRequestsDuration")}>
                            <TextInput
                                aria-label={t("accessRequestsDuration")}
                                id="access-request-duration"
                                isDisabled={isSubmitting || permanent}
                                min={1}
                                onChange={(_event, value) => setDurationAmount(value)}
                                step={1}
                                type="number"
                                value={durationAmount}
                            />
                        </FormGroup>
                        <FormGroup fieldId="access-request-duration-unit" label={t("accessRequestsDurationUnit")}>
                            <FormSelect id="access-request-duration-unit" isDisabled={isSubmitting || permanent}
                                aria-label={t("accessRequestsDurationUnit")}
                                onChange={(_event, value) => setDurationUnit(value as DurationUnit)} value={durationUnit}>
                                <FormSelectOption label={t("accessRequestsSeconds")} value="SECONDS" />
                                <FormSelectOption label={t("accessRequestsHours")} value="HOURS" />
                                <FormSelectOption label={t("accessRequestsDays")} value="DAYS" />
                            </FormSelect>
                        </FormGroup>
                        <p>{t("accessRequestsMaximumDuration", { duration: durationText(maximumDuration(selectedEntry), t) })}</p>
                        {selectedEntry.allowPermanent && <Checkbox
                            id="access-request-permanent"
                            isChecked={permanent}
                            isDisabled={isSubmitting}
                            label={t("accessRequestsPermanent")}
                            onChange={(_event, checked) => setPermanent(checked)}
                        />}
                        <ActionGroup>
                            <Button isDisabled={isSubmitting || !justification.trim()
                                || (!permanent && (selectedDurationSeconds(durationAmount, durationUnit) === undefined
                                    || selectedDurationSeconds(durationAmount, durationUnit)! > maximumDuration(selectedEntry)))}
                                type="submit" variant="primary">
                                {t("accessRequestsSubmitRequest")}
                            </Button>
                            <Button isDisabled={isSubmitting} type="button" variant="link" onClick={dismissDialog}>
                                {t("accessRequestsCancel")}
                            </Button>
                        </ActionGroup>
                    </Form>
                </Modal>
            )}
            </>
        </Page>
    );
}

type DurationUnit = "SECONDS" | "HOURS" | "DAYS";
const UNIT_SECONDS: Record<DurationUnit, number> = { SECONDS: 1, HOURS: 3600, DAYS: 86400 };
const RISK_DEFAULTS: Record<string, { defaultSeconds: number; maximumSeconds: number }> = {
    LOW: { defaultSeconds: 30 * 86400, maximumSeconds: 90 * 86400 },
    MEDIUM: { defaultSeconds: 7 * 86400, maximumSeconds: 30 * 86400 },
    HIGH: { defaultSeconds: 8 * 3600, maximumSeconds: 24 * 3600 },
    CRITICAL: { defaultSeconds: 3600, maximumSeconds: 4 * 3600 }
};

function defaultDuration(entry: RequestableEntitlement): number {
    return entry.defaultDurationSeconds ?? RISK_DEFAULTS[entry.riskLevel]?.defaultSeconds ?? 3600;
}

function maximumDuration(entry: RequestableEntitlement): number {
    return entry.maxDurationSeconds ?? RISK_DEFAULTS[entry.riskLevel]?.maximumSeconds ?? 3600;
}

function durationInput(seconds: number): { amount: string; unit: DurationUnit } {
    if (seconds % UNIT_SECONDS.DAYS === 0) return { amount: String(seconds / UNIT_SECONDS.DAYS), unit: "DAYS" };
    if (seconds % UNIT_SECONDS.HOURS === 0) return { amount: String(seconds / UNIT_SECONDS.HOURS), unit: "HOURS" };
    return { amount: String(seconds), unit: "SECONDS" };
}

function selectedDurationSeconds(amount: string, unit: DurationUnit): number | undefined {
    const number = Number(amount);
    const seconds = number * UNIT_SECONDS[unit];
    return amount.trim() && Number.isSafeInteger(number) && number > 0 && Number.isSafeInteger(seconds)
        ? seconds : undefined;
}

function durationText(seconds: number, t: (key: string) => string): string {
    const { amount, unit } = durationInput(seconds);
    return `${amount} ${t({ SECONDS: "accessRequestsSeconds", HOURS: "accessRequestsHours", DAYS: "accessRequestsDays" }[unit])}`;
}
