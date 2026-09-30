import {
    Alert, Button, FormSelect, FormSelectOption, TextInput
} from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError,
    type Entitlement,
    type EntitlementsAdminApi,
    type KeycloakReference
} from "../api/EntitlementsAdminApi";

export function KeycloakReferenceSelector({
    api,
    fieldId,
    isDisabled,
    isRequired = true,
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
    isRequired?: boolean;
    onSelect: (value: string, reference?: KeycloakReference) => void;
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
    const presentation = loadError ? presentEntitlementsAdminError(loadError) : undefined;
    const loadMessage = presentation
        ? `${t(presentation.messageKey)}${presentation.requestId ? ` (${presentation.requestId})` : ""}`
        : undefined;

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
            {loadMessage && <Alert isInline title={loadMessage} variant="danger" className="pf-v5-u-mt-sm" />}
            <FormSelect
                aria-label={t(selectionPlaceholder)}
                id={fieldId}
                isDisabled={isDisabled || loading || (first === 0 && Boolean(loadError))}
                isRequired={isRequired}
                onChange={(_event, nextValue) => {
                    const selected = references.find((reference) => reference.id === nextValue);
                    setRememberedSelection(selected);
                    setFirst(0);
                    onSelect(nextValue, selected);
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
