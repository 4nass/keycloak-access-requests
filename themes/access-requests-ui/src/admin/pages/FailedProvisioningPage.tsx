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
    Form,
    FormGroup,
    Label,
    Modal,
    ModalVariant,
    PageSection,
    Pagination,
    Spinner,
    Tab,
    Tabs,
    TabTitleText,
    Text,
    TextArea,
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
    type FailedProvisioningRequestPage,
    type ProvisioningFailureCode
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";

const PAGE_SIZE_OPTIONS = [10, 20, 50].map((value) => ({ title: String(value), value }));

export function FailedProvisioningPage() {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [requests, setRequests] = useState<FailedProvisioningRequestPage>();
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [state, setState] = useState<"OPEN" | "CLOSED">("OPEN");
    const [refresh, setRefresh] = useState(0);
    const [refreshError, setRefreshError] = useState<unknown>();
    const [retryTarget, setRetryTarget] = useState<FailedProvisioningRequest>();
    const [retryError, setRetryError] = useState<unknown>();
    const [isRetrying, setRetrying] = useState(false);
    const [closeTarget, setCloseTarget] = useState<FailedProvisioningRequest>();
    const [closeReason, setCloseReason] = useState("");
    const [closeError, setCloseError] = useState<unknown>();
    const [isClosing, setClosing] = useState(false);
    const [actionNotice, setActionNotice] = useState<"success" | "stillFailed" | "closed">();
    const retryInFlight = useRef(false);
    const closeInFlight = useRef(false);
    const locallyResolvedIds = useRef(new Set<string>());

    useEffect(() => {
        let active = true;
        setRefreshError(undefined);

        void api.failedProvisioningRequests({ page, size, state })
            .then((nextPage) => {
                if (active) {
                    const items = state === "OPEN"
                        ? nextPage.items.filter((request) => !locallyResolvedIds.current.has(request.id))
                        : nextPage.items;
                    const visiblePage = {
                        ...nextPage,
                        items,
                        total: Math.max(0, nextPage.total - (nextPage.items.length - items.length))
                    };
                    if (page > 0 && visiblePage.items.length === 0 && visiblePage.total > 0) {
                        setPage(Math.ceil(visiblePage.total / size) - 1);
                        return;
                    }
                    setRequests(visiblePage);
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
    }, [api, page, size, state, refresh]);

    const removeResolvedRequest = (id: string) => {
        locallyResolvedIds.current.add(id);
        setRequests((current) => {
            if (!current || state !== "OPEN" || !current.items.some((request) => request.id === id)) {
                return current;
            }
            return {
                ...current,
                items: current.items.filter((request) => request.id !== id),
                total: Math.max(0, current.total - 1)
            };
        });
    };

    const retry = async () => {
        if (!retryTarget || retryInFlight.current) {
            return;
        }
        retryInFlight.current = true;
        setRetrying(true);
        setRetryError(undefined);
        try {
            const outcome = await api.retryFailedProvisioning(retryTarget.id);
            if (outcome.provisioningStatus === "SUCCEEDED") {
                removeResolvedRequest(retryTarget.id);
            }
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
    const closeMessage = closeError ? errorText(closeError, t) : undefined;
    const validCloseReason = closeReason.trim().length >= 10 && closeReason.trim().length <= 1000;
    const dismissClose = () => {
        if (!closeInFlight.current) {
            setCloseTarget(undefined);
            setCloseReason("");
            setCloseError(undefined);
        }
    };
    const closeFailure = async () => {
        if (!closeTarget || !validCloseReason || closeInFlight.current) {
            return;
        }
        closeInFlight.current = true;
        setClosing(true);
        setCloseError(undefined);
        try {
            const closedId = closeTarget.id;
            await api.closeFailedProvisioning(closedId, closeReason.trim());
            removeResolvedRequest(closedId);
            setCloseTarget(undefined);
            setCloseReason("");
            setActionNotice("closed");
            setRefresh((value) => value + 1);
        } catch (error) {
            setCloseError(error);
        } finally {
            closeInFlight.current = false;
            setClosing(false);
        }
    };

    return (
        <>
            <PageSection variant="light">
                <Title headingLevel="h1">{t("accessRequestsAdminFailedProvisioning")}</Title>
                <TextContent>
                    <Text component="p">{t("accessRequestsAdminFailedProvisioningDescription")}</Text>
                </TextContent>
            </PageSection>
            <PageSection>
                <Tabs
                    activeKey={state}
                    aria-label={t("accessRequestsAdminFailedProvisioning")}
                    onSelect={(_event, nextState) => {
                        setRequests(undefined);
                        setRefreshError(undefined);
                        setActionNotice(undefined);
                        setPage(0);
                        setState(nextState === "CLOSED" ? "CLOSED" : "OPEN");
                    }}
                >
                    <Tab eventKey="OPEN" title={<TabTitleText>{t("accessRequestsAdminFailedProvisioningOpen")}</TabTitleText>} />
                    <Tab eventKey="CLOSED" title={<TabTitleText>{t("accessRequestsAdminFailedProvisioningClosed")}</TabTitleText>} />
                </Tabs>
                {actionNotice && (
                    <Alert
                        className="pf-v5-u-mb-lg"
                        isInline
                        title={t(actionNotice === "success"
                            ? "accessRequestsAdminFailedProvisioningRetrySuccess"
                            : actionNotice === "closed"
                                ? "accessRequestsAdminFailedProvisioningCloseSuccess"
                                : "accessRequestsAdminFailedProvisioningRetryStillFailed")}
                        variant={actionNotice === "stillFailed" ? "warning" : "success"}
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
                    <Toolbar aria-label={t(state === "OPEN"
                        ? "accessRequestsAdminFailedProvisioningOpen"
                        : "accessRequestsAdminFailedProvisioningClosed")}>
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
                    <DataList aria-label={t(state === "OPEN"
                        ? "accessRequestsAdminFailedProvisioningOpen"
                        : "accessRequestsAdminFailedProvisioningClosed")}>
                        {requests.items.map((request) => (
                            <FailedProvisioningItem
                                isClosed={state === "CLOSED"}
                                key={request.id}
                                onRetry={(item) => {
                                    setActionNotice(undefined);
                                    setRetryError(undefined);
                                    setRetryTarget(item);
                                }}
                                onClose={(item) => {
                                    setActionNotice(undefined);
                                    setCloseError(undefined);
                                    setCloseReason("");
                                    setCloseTarget(item);
                                }}
                                request={request}
                            />
                        ))}
                    </DataList>
                ) : requests ? (
                    <EmptyState>
                        <EmptyStateHeader
                            headingLevel="h2"
                            titleText={t(state === "OPEN"
                                ? "accessRequestsAdminFailedProvisioningEmpty"
                                : "accessRequestsAdminFailedProvisioningClosedEmpty")}
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
                    <Alert className="pf-v5-u-mb-lg" isInline title={t(failureCodeKey(retryTarget.failureCode))} variant="warning" />
                    <TextContent>
                        <Text component="p">{t("accessRequestsAdminFailedProvisioningRetryDescription")}</Text>
                        <Text component="small">{retryTarget.id}</Text>
                    </TextContent>
                </Modal>
            )}
            {closeTarget && (
                <Modal
                    actions={[
                        <Button
                            isDisabled={isClosing || !validCloseReason}
                            isLoading={isClosing}
                            key="close"
                            onClick={() => void closeFailure()}
                            variant="danger"
                        >
                            {t("accessRequestsAdminFailedProvisioningClose")}
                        </Button>,
                        <Button isDisabled={isClosing} key="cancel" onClick={dismissClose} variant="link">
                            {t("accessRequestsAdminCancel")}
                        </Button>
                    ]}
                    isOpen
                    onClose={dismissClose}
                    title={t("accessRequestsAdminFailedProvisioningClose")}
                    variant={ModalVariant.small}
                >
                    {closeMessage && <Alert className="pf-v5-u-mb-lg" isInline title={closeMessage} variant="danger" />}
                    <TextContent>
                        <Text component="p">{t("accessRequestsAdminFailedProvisioningCloseDescription")}</Text>
                        <Text component="small">{closeTarget.id}</Text>
                    </TextContent>
                    <Form className="pf-v5-u-mt-md" onSubmit={(event) => { event.preventDefault(); void closeFailure(); }}>
                        <FormGroup
                            fieldId="provisioning-closure-reason"
                            isRequired
                            label={t("accessRequestsAdminFailedProvisioningCloseReason")}
                        >
                            <TextArea
                                id="provisioning-closure-reason"
                                maxLength={1000}
                                onChange={(_event, value) => setCloseReason(value)}
                                value={closeReason}
                            />
                        </FormGroup>
                    </Form>
                </Modal>
            )}
        </>
    );
}

function FailedProvisioningItem({
    request,
    isClosed,
    onRetry,
    onClose
}: {
    request: FailedProvisioningRequest;
    isClosed: boolean;
    onRetry: (request: FailedProvisioningRequest) => void;
    onClose: (request: FailedProvisioningRequest) => void;
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
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminFailureCause")}</DescriptionListTerm>
                                <DescriptionListDescription>{t(failureCodeKey(request.failureCode))}</DescriptionListDescription>
                            </DescriptionListGroup>
                            {isClosed && (
                                <>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningClosedAt")}</DescriptionListTerm>
                                        <DescriptionListDescription>{request.closedAt
                                            ? formatDate(request.closedAt, i18n.language, t)
                                            : t("accessRequestsAdminNotAvailable")}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningClosedBy")}</DescriptionListTerm>
                                        <DescriptionListDescription>{request.closedBy ?? t("accessRequestsAdminNotAvailable")}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>{t("accessRequestsAdminFailedProvisioningCloseReason")}</DescriptionListTerm>
                                        <DescriptionListDescription>{request.closureReason ?? t("accessRequestsAdminNotAvailable")}</DescriptionListDescription>
                                    </DescriptionListGroup>
                                </>
                            )}
                        </DescriptionList>
                    </DataListCell>,
                    <DataListCell key="status" width={1}>
                        <Label color={isClosed ? "grey" : "red"}>
                            {t(isClosed
                                ? "accessRequestsAdminFailedProvisioningClosed"
                                : "accessRequestsAdminProvisioningFailed")}
                        </Label>
                    </DataListCell>
                ]} />
                {!isClosed && <DataListAction
                    aria-label={t("accessRequestsAdminFailedProvisioningRetry")}
                    aria-labelledby={titleId}
                    id={`failed-provisioning-action-${request.id}`}
                >
                    <Button onClick={() => onRetry(request)} type="button" variant="secondary">
                        {t("accessRequestsAdminFailedProvisioningRetry")}
                    </Button>
                    <Button onClick={() => onClose(request)} type="button" variant="link">
                        {t("accessRequestsAdminFailedProvisioningClose")}
                    </Button>
                </DataListAction>}
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

function failureCodeKey(code: ProvisioningFailureCode) {
    const keys: Record<ProvisioningFailureCode, string> = {
        REQUESTER_MISSING: "accessRequestsAdminFailureRequesterMissing",
        RESOURCE_MISSING: "accessRequestsAdminFailureResourceMissing",
        RESOURCE_TYPE_MISMATCH: "accessRequestsAdminFailureResourceTypeMismatch",
        REALM_MISMATCH: "accessRequestsAdminFailureRealmMismatch",
        PROVIDER_UNAVAILABLE: "accessRequestsAdminFailureProviderUnavailable",
        UNEXPECTED_FAILURE: "accessRequestsAdminFailureUnexpected",
        UNKNOWN: "accessRequestsAdminFailureUnknown"
    };
    return keys[code] ?? keys.UNKNOWN;
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
