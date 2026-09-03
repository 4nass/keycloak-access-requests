import { Page } from "@keycloak/keycloak-account-ui";
import {
    ActionGroup,
    Button,
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
    Modal,
    SearchInput,
    TextArea
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
};

type AccessRequestSubmission = {
    entitlementId: string;
    justification: string;
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

    const closeDialog = () => {
        setSelectedEntry(undefined);
        setJustification("");
    };

    const dismissDialog = () => {
        if (!isSubmitting) {
            closeDialog();
        }
    };

    const submit = async () => {
        if (!selectedEntry || !justification.trim() || isSubmitting) {
            return;
        }

        setIsSubmitting(true);

        try {
            await onRequest({
                entitlementId: selectedEntry.id,
                justification: justification.trim()
            });
        } catch (error) {
            addError("accessRequestsRequestSubmissionFailed", error);
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
                                                <Button type="button" variant="primary" onClick={() => setSelectedEntry(entry)}>
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
                        <ActionGroup>
                            <Button isDisabled={isSubmitting || !justification.trim()} type="submit" variant="primary">
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
