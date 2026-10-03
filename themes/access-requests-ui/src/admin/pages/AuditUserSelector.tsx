import { Alert, FormSelect, FormSelectOption, TextInput } from "@patternfly/react-core";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import {
    presentEntitlementsAdminError, type AuditUser, type EntitlementsAdminApi
} from "../api/EntitlementsAdminApi";

const SYSTEM_ACTOR_ID = "access-requests-expiration";

export function AuditUserSelector({ api, fieldId, value, onSelect, includeSystem = false, searchLabel }: {
    api: EntitlementsAdminApi;
    fieldId: string;
    value: AuditUser | null;
    onSelect: (user: AuditUser | null) => void;
    includeSystem?: boolean;
    searchLabel: string;
}) {
    const { t } = useTranslation();
    const [search, setSearch] = useState("");
    const [users, setUsers] = useState<AuditUser[]>([]);
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<unknown>();

    useEffect(() => {
        const term = search.trim();
        if (term.length < 2) {
            setUsers([]);
            setLoading(false);
            setLoadError(undefined);
            return;
        }
        let active = true;
        const controller = new AbortController();
        setUsers([]);
        setLoading(true);
        setLoadError(undefined);
        const timer = window.setTimeout(() => {
            void api.auditUsers(term, controller.signal).then((result) => {
                if (active) setUsers(result.items);
            }).catch((error: unknown) => {
                if (active) {
                    setUsers([]);
                    setLoadError(error);
                }
            }).finally(() => {
                if (active) setLoading(false);
            });
        }, 300);
        return () => {
            active = false;
            window.clearTimeout(timer);
            controller.abort();
        };
    }, [api, search]);

    const systemActor = includeSystem ? {
        id: SYSTEM_ACTOR_ID, name: t("accessRequestsAdminSystemActor"), username: ""
    } : null;
    const options = [value, systemActor, ...users].filter((user, index, all): user is AuditUser =>
        user !== null && all.findIndex((candidate) => candidate?.id === user.id) === index);
    const selected = options.find((user) => user.id === value?.id);
    const label = (user: AuditUser) => user.username && user.name !== user.username
        ? `${user.name} (${user.username})` : user.name;
    const presentation = loadError ? presentEntitlementsAdminError(loadError) : undefined;

    return <>
        <TextInput id={`${fieldId}-search`} type="search" aria-label={t(searchLabel)} value={search}
            onChange={(_event, next) => setSearch(next)} />
        {presentation && <Alert isInline variant="danger" className="pf-v5-u-mt-sm"
            title={`${t(presentation.messageKey)}${presentation.requestId ? ` (${presentation.requestId})` : ""}`} />}
        <FormSelect id={fieldId} value={selected?.id ?? ""}
            onChange={(_event, id) => onSelect(options.find((user) => user.id === id) ?? null)}>
            <FormSelectOption value="" label={t("accessRequestsAdminEventsAnyUser")} />
            {options.map((user) => <FormSelectOption key={user.id} value={user.id} label={label(user)} />)}
            {loading && <FormSelectOption isDisabled value="loading" label={t("accessRequestsAdminEventsUsersLoading")} />}
            {!loading && !loadError && search.trim().length < 2 && !value &&
                <FormSelectOption isDisabled value="hint" label={t("accessRequestsAdminEventsEnterName")} />}
            {!loading && !loadError && search.trim().length >= 2 && users.length === 0 &&
                <FormSelectOption isDisabled value="empty" label={t("accessRequestsAdminEventsNoUsers")} />}
        </FormSelect>
    </>;
}
