import { useState } from "react";
import { useTranslation } from "react-i18next";

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
};

export function RequestAccessPage({ entries, onRequest }: RequestAccessPageProps) {
    const { t } = useTranslation();
    const [selectedEntry, setSelectedEntry] = useState<RequestableEntitlement>();
    const [justification, setJustification] = useState("");

    const closeDialog = () => {
        setSelectedEntry(undefined);
        setJustification("");
    };

    const submit = () => {
        if (!selectedEntry || !justification.trim()) {
            return;
        }

        void onRequest({
            entitlementId: selectedEntry.id,
            justification: justification.trim()
        });
        closeDialog();
    };

    return (
        <section aria-labelledby="request-access-title">
            <h1 id="request-access-title">{t("accessRequestsRequestAccess")}</h1>
            {entries.map((entry) => (
                <article key={entry.id} aria-label={entry.name}>
                    <h2>{entry.name}</h2>
                    <p>{entry.description}</p>
                    <dl>
                        <div>
                            <dt>{t("accessRequestsResourceType")}</dt>
                            <dd>{entry.resourceType}</dd>
                        </div>
                        <div>
                            <dt>{t("accessRequestsRiskLabel")}</dt>
                            <dd>{t("accessRequestsRisk", { riskLevel: entry.riskLevel })}</dd>
                        </div>
                    </dl>
                    {entry.alreadyGranted ? (
                        <p>{t("accessRequestsAlreadyGranted")}</p>
                    ) : entry.pendingRequest ? (
                        <p>{t("accessRequestsRequestPending")}</p>
                    ) : (
                        <button type="button" onClick={() => setSelectedEntry(entry)}>
                            {t("accessRequestsRequestAccess")}
                        </button>
                    )}
                </article>
            ))}
            {selectedEntry && (
                <div
                    aria-label={t("accessRequestsRequestAccessTo", { entitlement: selectedEntry.name })}
                    aria-modal="true"
                    role="dialog"
                >
                    <h2>{t("accessRequestsRequestAccessTo", { entitlement: selectedEntry.name })}</h2>
                    <label htmlFor="justification">{t("accessRequestsJustification")}</label>
                    <textarea
                        id="justification"
                        onChange={(event) => setJustification(event.target.value)}
                        value={justification}
                    />
                    <button type="button" onClick={closeDialog}>
                        {t("accessRequestsCancel")}
                    </button>
                    <button type="button" disabled={!justification.trim()} onClick={submit}>
                        {t("accessRequestsSubmitRequest")}
                    </button>
                </div>
            )}
        </section>
    );
}
