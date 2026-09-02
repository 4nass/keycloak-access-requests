import { useState } from "react";

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
            <h1 id="request-access-title">Request access</h1>
            {entries.map((entry) => (
                <article key={entry.id} aria-label={entry.name}>
                    <h2>{entry.name}</h2>
                    <p>{entry.description}</p>
                    <dl>
                        <div>
                            <dt>Resource type</dt>
                            <dd>{entry.resourceType}</dd>
                        </div>
                        <div>
                            <dt>Risk</dt>
                            <dd>Risk: {entry.riskLevel}</dd>
                        </div>
                    </dl>
                    {entry.alreadyGranted ? (
                        <p>Already granted</p>
                    ) : entry.pendingRequest ? (
                        <p>Request pending</p>
                    ) : (
                        <button type="button" onClick={() => setSelectedEntry(entry)}>
                            Request access
                        </button>
                    )}
                </article>
            ))}
            {selectedEntry && (
                <div aria-label={`Request access to ${selectedEntry.name}`} aria-modal="true" role="dialog">
                    <h2>Request access to {selectedEntry.name}</h2>
                    <label htmlFor="justification">Justification</label>
                    <textarea
                        id="justification"
                        onChange={(event) => setJustification(event.target.value)}
                        value={justification}
                    />
                    <button type="button" onClick={closeDialog}>
                        Cancel
                    </button>
                    <button type="button" disabled={!justification.trim()} onClick={submit}>
                        Submit request
                    </button>
                </div>
            )}
        </section>
    );
}
