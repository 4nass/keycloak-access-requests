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
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type NotificationDelivery,
    type NotificationDeliverySummary
} from "../api/EntitlementsAdminApi";
import { useEntitlementsAdminApi } from "../api/useEntitlementsAdminApi";

const PAGE_SIZE_OPTIONS = [10, 20, 50].map((value) => ({ title: String(value), value }));

export function NotificationDeliveryPage() {
    const { t } = useTranslation();
    const api = useEntitlementsAdminApi();
    const [deliveries, setDeliveries] = useState<{ items: NotificationDelivery[]; total: number }>();
    const [summary, setSummary] = useState<NotificationDeliverySummary>();
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [initialLoading, setInitialLoading] = useState(true);
    const [refreshError, setRefreshError] = useState<unknown>();
    const [retryTarget, setRetryTarget] = useState<NotificationDelivery>();
    const [retryError, setRetryError] = useState<unknown>();
    const [isRetrying, setRetrying] = useState(false);
    const [actionNotice, setActionNotice] = useState<string>();

    const load = useCallback(async () => {
        setRefreshError(undefined);
        try {
            const [nextSummary, failedDeliveries] = await Promise.all([
                api.notificationDeliverySummary(),
                api.notificationDeliveries({ page, size })
            ]);
            setSummary(nextSummary);
            setDeliveries({ items: failedDeliveries.items, total: failedDeliveries.total });
        } catch (error) {
            setRefreshError(error);
        } finally {
            setInitialLoading(false);
        }
    }, [api, page, size]);

    useEffect(() => {
        void load();
    }, [load]);

    const openRetry = (delivery: NotificationDelivery) => {
        setActionNotice(undefined);
        setRetryError(undefined);
        setRetryTarget(delivery);
    };

    const closeRetry = () => {
        if (!isRetrying) {
            setRetryTarget(undefined);
            setRetryError(undefined);
        }
    };

    const retry = async () => {
        if (!retryTarget) {
            return;
        }
        setRetrying(true);
        setRetryError(undefined);
        try {
            await api.retryNotificationDelivery(retryTarget.id);
            setRetryTarget(undefined);
            setActionNotice(t("accessRequestsAdminNotificationDeliveryRetryQueued"));
            await load();
        } catch (error) {
            setRetryError(error);
        } finally {
            setRetrying(false);
        }
    };

    const refreshMessage = refreshError ? errorText(refreshError, t) : undefined;
    const retryMessage = retryError ? errorText(retryError, t) : undefined;

    return (
        <>
            <PageSection variant="light">
                <Title headingLevel="h1">{t("accessRequestsAdminNotificationDelivery")}</Title>
                <TextContent>
                    <Text component="p">{t("accessRequestsAdminNotificationDeliveryDescription")}</Text>
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
                {summary && <DeliverySummary summary={summary} />}
                <Toolbar aria-label={t("accessRequestsAdminNotificationDelivery")}>
                    <ToolbarContent>
                        <ToolbarItem>
                            <Title headingLevel="h2" size="lg">{t("accessRequestsAdminNotificationDeliveryFailed")}</Title>
                        </ToolbarItem>
                        {deliveries && deliveries.total > 0 && (
                            <ToolbarItem align={{ default: "alignRight" }} variant="pagination">
                                <DeliveryPagination
                                    onPageChange={setPage}
                                    onSizeChange={(nextSize) => {
                                        setPage(0);
                                        setSize(nextSize);
                                    }}
                                    page={page}
                                    size={size}
                                    total={deliveries.total}
                                    variant="top"
                                />
                            </ToolbarItem>
                        )}
                    </ToolbarContent>
                </Toolbar>
                {initialLoading && !deliveries ? (
                    <EmptyState><Spinner aria-label={t("loading")} /></EmptyState>
                ) : deliveries?.items.length ? (
                    <DataList aria-label={t("accessRequestsAdminNotificationDeliveryFailed")}>
                        {deliveries.items.map((delivery) => (
                            <NotificationDeliveryListItem delivery={delivery} key={delivery.id} onRetry={openRetry} />
                        ))}
                    </DataList>
                ) : (
                    <EmptyState>
                        <EmptyStateHeader headingLevel="h2" titleText={t("accessRequestsAdminNotificationDeliveryEmpty")} />
                        <EmptyStateBody>{t("accessRequestsAdminNotificationDeliveryDescription")}</EmptyStateBody>
                    </EmptyState>
                )}
                {deliveries && deliveries.total > 0 && (
                    <DeliveryPagination
                        onPageChange={setPage}
                        onSizeChange={(nextSize) => {
                            setPage(0);
                            setSize(nextSize);
                        }}
                        page={page}
                        size={size}
                        total={deliveries.total}
                        variant="bottom"
                    />
                )}
            </PageSection>
            {retryTarget && (
                <RetryDeliveryDialog
                    delivery={retryTarget}
                    error={retryMessage}
                    isRetrying={isRetrying}
                    onClose={closeRetry}
                    onRetry={retry}
                />
            )}
        </>
    );
}

function DeliverySummary({ summary }: { summary: NotificationDeliverySummary }) {
    const { t } = useTranslation();
    return (
        <DescriptionList aria-label={t("accessRequestsAdminNotificationDeliverySummary")} isHorizontal isCompact>
            <DescriptionListGroup>
                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryPending")}</DescriptionListTerm>
                <DescriptionListDescription><Label color="blue">{summary.pending}</Label></DescriptionListDescription>
            </DescriptionListGroup>
            <DescriptionListGroup>
                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryProcessing")}</DescriptionListTerm>
                <DescriptionListDescription><Label color="orange">{summary.processing}</Label></DescriptionListDescription>
            </DescriptionListGroup>
            <DescriptionListGroup>
                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryFailed")}</DescriptionListTerm>
                <DescriptionListDescription><Label color="red">{summary.failed}</Label></DescriptionListDescription>
            </DescriptionListGroup>
            <DescriptionListGroup>
                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryDelivered")}</DescriptionListTerm>
                <DescriptionListDescription><Label color="green">{summary.delivered}</Label></DescriptionListDescription>
            </DescriptionListGroup>
            <DescriptionListGroup>
                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryDiscarded")}</DescriptionListTerm>
                <DescriptionListDescription><Label color="grey">{summary.discarded}</Label></DescriptionListDescription>
            </DescriptionListGroup>
        </DescriptionList>
    );
}

function NotificationDeliveryListItem({
    delivery,
    onRetry
}: {
    delivery: NotificationDelivery;
    onRetry: (delivery: NotificationDelivery) => void;
}) {
    const { i18n, t } = useTranslation();
    const titleId = `notification-delivery-${delivery.id}`;
    return (
        <DataListItem aria-labelledby={titleId}>
            <DataListItemRow>
                <DataListItemCells dataListCells={[
                    <DataListCell key="delivery" width={3}>
                        <Title headingLevel="h3" id={titleId} size="md">{notificationTypeLabel(delivery.notificationType, t)}</Title>
                        <Text component="small">{t("accessRequestsAdminNotificationDeliveryRequestId")}: {delivery.requestId}</Text>
                        <Text component="small">{t("accessRequestsAdminNotificationDeliveryEntitlementId")}: {delivery.entitlementId}</Text>
                    </DataListCell>,
                    <DataListCell key="recipient" width={2}>
                        <DescriptionList isCompact isHorizontal>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryRecipient")}</DescriptionListTerm>
                                <DescriptionListDescription>{delivery.recipientId}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryRecipientType")}</DescriptionListTerm>
                                <DescriptionListDescription>{recipientTypeLabel(delivery.recipientType, t)}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryAttempts")}</DescriptionListTerm>
                                <DescriptionListDescription>{delivery.attemptCount}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>{t("accessRequestsAdminNotificationDeliveryLastAttempt")}</DescriptionListTerm>
                                <DescriptionListDescription>{formatDate(delivery.lastAttemptAt, i18n.language, t)}</DescriptionListDescription>
                            </DescriptionListGroup>
                        </DescriptionList>
                    </DataListCell>,
                    <DataListCell key="state" width={1}>
                        <Label color="red">{t("accessRequestsAdminNotificationDeliveryFailed")}</Label>
                    </DataListCell>
                ]} />
                <DataListAction
                    aria-label={t("accessRequestsAdminNotificationDeliveryRetry")}
                    aria-labelledby={titleId}
                    id={`notification-delivery-actions-${delivery.id}`}
                >
                    <Button onClick={() => onRetry(delivery)} type="button" variant="secondary">
                        {t("accessRequestsAdminNotificationDeliveryRetry")}
                    </Button>
                </DataListAction>
            </DataListItemRow>
        </DataListItem>
    );
}

function RetryDeliveryDialog({
    delivery,
    error,
    isRetrying,
    onClose,
    onRetry
}: {
    delivery: NotificationDelivery;
    error?: string;
    isRetrying: boolean;
    onClose: () => void;
    onRetry: () => Promise<void>;
}) {
    const { t } = useTranslation();
    const title = t("accessRequestsAdminNotificationDeliveryRetry");
    return (
        <Modal
            isOpen
            onClose={onClose}
            title={title}
            variant={ModalVariant.small}
            actions={[
                <Button isLoading={isRetrying} key="retry" onClick={() => void onRetry()}>
                    {title}
                </Button>,
                <Button isDisabled={isRetrying} key="cancel" onClick={onClose} variant="link">
                    {t("accessRequestsAdminCancel")}
                </Button>
            ]}
        >
            {error && <Alert isInline title={error} variant="danger" className="pf-v5-u-mb-lg" />}
            <TextContent>
                <Text component="p">{t("accessRequestsAdminNotificationDeliveryRetryDescription")}</Text>
                <Text component="small">{delivery.id}</Text>
            </TextContent>
        </Modal>
    );
}

function DeliveryPagination({
    onPageChange,
    onSizeChange,
    page,
    size,
    total,
    variant
}: {
    onPageChange: (page: number) => void;
    onSizeChange: (size: number) => void;
    page: number;
    size: number;
    total: number;
    variant: "top" | "bottom";
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
            widgetId="access-request-notification-deliveries"
        />
    );
}

function notificationTypeLabel(
    type: NotificationDelivery["notificationType"],
    translate: (key: string) => string
) {
    return {
        PROVISIONING_FAILED: translate("accessRequestsAdminNotificationTypeProvisioningFailed"),
        PROVISIONING_CLOSED: translate("accessRequestsAdminNotificationTypeProvisioningClosed"),
        REQUEST_APPROVED: translate("accessRequestsAdminNotificationTypeRequestApproved"),
        REQUEST_REJECTED: translate("accessRequestsAdminNotificationTypeRequestRejected"),
        REQUEST_SUBMITTED: translate("accessRequestsAdminNotificationTypeRequestSubmitted")
    }[type];
}

function recipientTypeLabel(
    type: NotificationDelivery["recipientType"],
    translate: (key: string) => string
) {
    return type === "USER"
        ? translate("accessRequestsAdminNotificationRecipientTypeUser")
        : translate("accessRequestsAdminNotificationRecipientTypeRealmRole");
}

function formatDate(value: string | undefined, locale: string, translate: (key: string) => string) {
    if (!value) {
        return translate("accessRequestsAdminNotAvailable");
    }
    const instant = new Date(value);
    if (Number.isNaN(instant.getTime())) {
        return translate("accessRequestsAdminNotAvailable");
    }
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(instant);
}

function errorText(error: unknown, translate: (key: string) => string) {
    const presentation = presentEntitlementsAdminError(error);
    const message = translate(presentation.messageKey);
    return presentation.requestId ? `${message} (${presentation.requestId})` : message;
}
