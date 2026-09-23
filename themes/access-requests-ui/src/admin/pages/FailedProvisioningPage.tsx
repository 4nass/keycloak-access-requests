import {
    Alert,
    Button,
    ButtonVariant,
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
    Label,
    Modal,
    ModalVariant,
    PageSection,
    Pagination,
    Spinner,
    Text,
    TextContent,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem
} from "@patternfly/react-core";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type FailedProvisioningRequest,
    type FailedProvisioningRequestPage
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";

const PAGE_SIZE_OPTIONS = [10, 20, 50].map((value) => ({ title: String(value), value }));

export function FailedProvisioningPage() {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [requests, setRequests] = useState<FailedProvisioningRequestPage>();
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [refresh, setRefresh] = useState(0);
    const [refreshError, setRefreshError] = useState<unknown>();
    const [retryTarget, setRetryTarget] = useState<FailedProvisioningRequest>();
    const [retryError, setRetryError] = useState<unknown>();
    const [isRetrying, setRetrying] = useState(false);
    const [actionNotice, setActionNotice] = useState<"success" | "stillFailed">();
    const retryInFlight = useRef(false);

    useEffect(() => {
        let active = true;
        setRefreshError(undefined);

        void api.failedProvisioningRequests({ page, size })
            .then((nextPage) => {
                if (active) {
                    if (page > 0 && nextPage.items.length === 0 && nextPage.total > 0) {
                        setPage(Math.ceil(nextPage.total / size) - 1);
                        return;
                    }
                    setRequests(nextPage);
                }
            })
            .catch((error: unknown) => {
                if (active) {
                    setRefreshError(error);
                }
            });

        return () => {
            active = false;
        };
    }, [api, page, size, refresh]);

    const retry = async () => {
        if (!retryTarget || retryInFlight.current) {
            return;
        }
        retryInFlight.current = true;
        setRetrying(true);
        setRetryError(undefined);
        try {
            const outcome = await api.retryFailedProvisioning(retryTarget.id);
            setRetryTarget(undefined);
            setActionNotice(outcome.provisioningStatus === "SUCCEEDED" ? "success" : "stillFailed");
            setRefresh((value) => value + 1);
        } catch (error) {
            setRetryError(error);
        } finally {
            retryInFlight.current = false;
            setRetrying(false);
        }
    };

    const refreshMessage = refreshError ? errorText(refreshError, t) : undefined;
    const retryMessage = retryError ? errorText(retryError, t) : undefined;

    return (
        <>
            <PageSection variant="light">
                <Title headingLevel="h1">{t("accessRequestsAdminFailedProvisioning")}</Title>
                <TextContent>
                    <Text component="p">{t("accessRequestsAdminFailedProvisioningDescription")}</Text>
                </TextContent>
            </PageSection>
            <PageSection>
                {actionNotice && (
                    <Alert
                        className="pf-v5-u-mb-lg"
                        isInline
                        title={t(actionNotice === "success"
                            ? "accessRequestsAdminFailedProvisioningRetrySuccess"
                            : "accessRequestsAdminFailedProvisioningRetryStillFailed")}
                        variant={actionNotice === "success" ? "success" : "warning"}
                    />
                )}
                {refreshMessage && (
                    <Alert
                        actionClose={<Button aria-label={t("close")} onClick={() => setRefreshError(undefined)} variant={ButtonVariant.plain} />}
                        actionLinks={<Button onClick={() => setRefresh((value) => value + 1)} variant="link">{t("reload")}</Button>}
                        className="pf-v5-u-mb-lg"
                        isInline
                        title={refreshMessage}
                        variant="danger"
                    />
                )}
                {requests && requests.total > 0 && (
                    <Toolbar aria-label={t("accessRequestsAdminFailedProvisioning")}>
                        <ToolbarContent>
                            <ToolbarItem align={{ default: "alignRight" }} variant="pagination">
                                <Pagination
                                    itemCount={requests.total}
                                    onPerPageSelect={(_event, nextSize) => {
                                        setPage(0);
                                        setSize(nextSize);
                                    }}
                                    onSetPage={(_event, nextPage) => setPage(nextPage - 1)}
                                    page={page + 1}
                                    perPage={size}
                                    perPageOptions={PAGE_SIZE_OPTIONS}
                                    widgetId="access-request-provisioning-failures"
                                />
                            </ToolbarItem>
                        </ToolbarContent>
                    </Toolbar>
                )}
                {!requests && !refreshError ? (
                    <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
                ) : requests?.items.length ? (
                    <DataList aria-label={t("accessRequestsAdminFailedProvisioning")}>
                        {requests.items.map((request) => (
                            <FailedProvisioningItem
                                key={request.id}
                                onRetry={(item) => {
                                    setActionNotice(undefined);
                                    setRetryError(undefined);
                                    setRetryTarget(item);
                                }}
                                request={request}
                            />
                        ))}
                    </DataList>
                ) : !refreshError ? (
                    <EmptyState>
                        <EmptyStateHeader
                            headingLevel="h2"
                            titleText={t("accessRequestsAdminFailedProvisioningEmpty")}
                        />
                        <EmptyStateBody>{t("accessRequestsAdminFailedProvisioningDescription")}</EmptyStateBody>
                    </EmptyState>
                ) : null}
            </PageSection>
            {retryTarget && (
                <Modal
                    actions={[
                        <Button isDisabled={isRetrying} isLoading={isRetrying} key="retry" onClick={() => void retry()}>
                            {t("accessRequestsAdminFailedProvisioningRetry")}
                        </Button>,
                        <Button
                            isDisabled={isRetrying}
                            key="cancel"
                            onClick={() => {
                                setRetryTarget(undefined);
                                setRetryError(undefined);
                            }}
                            variant="link"
                        >
                            {t("accessRequestsAdminCancel")}
                        </Button>
                    ]}
                    isOpen
                    onClose={() => {
                        if (!retryInFlight.current) {
                            setRetryTarget(undefined);
                            setRetryError(undefined);
                        }
                    }}
                    title={t("accessRequestsAdminFailedProvisioningRetry")}
                    variant={ModalVariant.small}
                >
                    {retryMessage && <Alert className="pf-v5-u-mb-lg" isInline title={retryMessage} variant="danger" />}
                    <TextContent>
                        <Text component="p">{t("accessRequestsAdminFailedProvisioningRetryDescription")}</Text>
                        <Text component="small">{retryTarget.id}</Text>
                    </TextContent>
                </Modal>
            )}
        </>
    );
}

function FailedProvisioningItem({
    request,
    onRetry
}: {
    request: FailedProvisioningRequest;
    onRetry: (request: FailedProvisioningRequest) => void;
}) {
    const { i18n, t } = useTranslation();
    const titleId = `failed-provisioning-${request.id}`;

    return (
        <DataListItem aria-labelledby={titleId}>
            <DataListItemRow>
                <DataListItemCells dataListCells={[
                    <DataListCell key="resource" width={3}>
                        <Title headingLevel="h3" id={titleId} size="md">{request.resourceName}</Title>
                        <Text component="small">{request.id}</Text>
                    </DataListCell>,
                    <DataListCell key="metadata" width={2}>
                        <DescriptionList isCompact isHorizontal>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningRequester")}</DescriptionListTerm>
                                <DescriptionListDescription>{request.requesterId}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningEntitlement")}</DescriptionListTerm>
                                <DescriptionListDescription>{request.entitlementId}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningResource")}</DescriptionListTerm>
                                <DescriptionListDescription>{t(resourceTypeKey(request.resourceType))}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminUpdated")}</DescriptionListTerm>
                                <DescriptionListDescription>{formatDate(request.updatedAt, i18n.language, t)}</DescriptionListDescription>
                            </DescriptionListGroup>
                        </DescriptionList>
                    </DataListCell>,
                    <DataListCell key="status" width={1}>
                        <Label color="red">{t("accessRequestsAdminProvisioningFailed")}</Label>
                    </DataListCell>
                ]} />
                <DataListAction
                    aria-label={t("accessRequestsAdminFailedProvisioningRetry")}
                    aria-labelledby={titleId}
                    id={`failed-provisioning-action-${request.id}`}
                >
                    <Button onClick={() => onRetry(request)} type="button" variant="secondary">
                        {t("accessRequestsAdminFailedProvisioningRetry")}
                    </Button>
                </DataListAction>
            </DataListItemRow>
        </DataListItem>
    );
}

function resourceTypeKey(type: FailedProvisioningRequest["resourceType"]) {
    return {
        REALM_ROLE: "accessRequestsAdminResourceTypeRealmRole",
        CLIENT_ROLE: "accessRequestsAdminResourceTypeClientRole",
        GROUP: "accessRequestsAdminResourceTypeGroup"
    }[type];
}

function formatDate(value: string, locale: string, translate: (key: string) => string) {
    const instant = new Date(value);
    return Number.isNaN(instant.getTime())
        ? translate("accessRequestsAdminNotAvailable")
        : new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(instant);
}

function errorText(error: unknown, translate: (key: string) => string) {
    const presentation = presentEntitlementsAdminError(error);
    const message = translate(presentation.messageKey);
    return presentation.requestId ? `${message} (${presentation.requestId})` : message;
}
