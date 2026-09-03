import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";

import { AccessibleDialog } from "./AccessibleDialog";
import { AccessRequestPagination, type AccessRequestPaginationState } from "./AccessRequestPagination";

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
};

function errorMessage(error: unknown) {
    return error instanceof Error ? error.message : String(error);
}

export function RequestAccessPage({ entries, onRequest, onRefresh, pagination }: RequestAccessPageProps) {
    const { t } = useTranslation();
    const [selectedEntry, setSelectedEntry] = useState<RequestableEntitlement>();
    const [justification, setJustification] = useState("");
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [submissionError, setSubmissionError] = useState<string>();
    const justificationRef = useRef<HTMLTextAreaElement>(null);

    const closeDialog = () => {
        setSelectedEntry(undefined);
        setJustification("");
        setSubmissionError(undefined);
    };

    const submit = async () => {
        if (!selectedEntry || !justification.trim() || isSubmitting) {
            return;
        }

        setIsSubmitting(true);
        setSubmissionError(undefined);

        try {
            await onRequest({
                entitlementId: selectedEntry.id,
                justification: justification.trim()
            });
        } catch (error) {
            setSubmissionError(errorMessage(error));
            setIsSubmitting(false);
            return;
        }

        closeDialog();
        await onRefresh?.();
        setIsSubmitting(false);
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
            <AccessRequestPagination pagination={pagination} />
            {selectedEntry && (
                <AccessibleDialog
                    ariaLabel={t("accessRequestsRequestAccessTo", { entitlement: selectedEntry.name })}
                    initialFocusRef={justificationRef}
                    onClose={closeDialog}
                >
                    <h2>{t("accessRequestsRequestAccessTo", { entitlement: selectedEntry.name })}</h2>
                    <label htmlFor="justification">{t("accessRequestsJustification")}</label>
                    <textarea
                        id="justification"
                        onChange={(event) => setJustification(event.target.value)}
                        ref={justificationRef}
                        value={justification}
                    />
                    {submissionError && <p role="alert">{submissionError}</p>}
                    <button type="button" disabled={isSubmitting} onClick={closeDialog}>
                        {t("accessRequestsCancel")}
                    </button>
                    <button type="button" disabled={isSubmitting || !justification.trim()} onClick={() => void submit()}>
                        {t("accessRequestsSubmitRequest")}
                    </button>
                </AccessibleDialog>
            )}
        </section>
    );
}
