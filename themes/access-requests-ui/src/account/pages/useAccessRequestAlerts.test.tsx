import { createInstance } from "i18next";
import { render } from "@testing-library/react";
import { useEffect } from "react";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

const accountAlerts = vi.hoisted(() => ({
    addAlert: vi.fn()
}));

vi.mock("@keycloak/keycloak-account-ui", () => ({
    useAccountAlerts: () => accountAlerts
}));

import { useAccessRequestAlerts } from "./useAccessRequestAlerts";

const i18n = createInstance();

await i18n.init({
    initImmediate: false,
    lng: "en",
    resources: {
        en: {
            translation: {
                accessRequestsErrorConflict: "The request has changed. Refresh the page and try again.",
                accessRequestsErrorReference: "Reference: {{requestId}}"
            }
        }
    }
});

function ErrorReporter({ error }: { error: unknown }) {
    const { addError } = useAccessRequestAlerts();

    useEffect(() => {
        addError(error);
    }, [addError, error]);

    return null;
}

describe("Access request alerts", () => {
    beforeEach(() => {
        accountAlerts.addAlert.mockReset();
    });

    it("does not pass an API message to the Account Console alert", () => {
        const error = Object.assign(new Error("SQL connection to internal-host-01 failed."), {
            code: "CONCURRENT_MODIFICATION",
            requestId: "request-42",
            status: 409
        });

        render(
            <I18nextProvider i18n={i18n}>
                <ErrorReporter error={error} />
            </I18nextProvider>
        );

        expect(accountAlerts.addAlert).toHaveBeenCalledWith(
            "The request has changed. Refresh the page and try again.",
            "danger",
            "Reference: request-42"
        );
        expect(accountAlerts.addAlert.mock.calls.flat().join(" ")).not.toContain("internal-host-01");
    });
});
