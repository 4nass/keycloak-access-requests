import { createInstance } from "i18next";
import { render, screen } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { describe, expect, it, vi } from "vitest";

import { LoadError } from "./AccessRequestRoutePageSupport";

const i18n = createInstance();

await i18n.init({
    initImmediate: false,
    lng: "en",
    resources: {
        en: {
            translation: {
                accessRequestsErrorConflict: "The request has changed. Refresh the page and try again.",
                accessRequestsErrorReference: "Reference: {{requestId}}",
                accessRequestsLoadError: "Unable to load access requests.",
                accessRequestsRetry: "Retry"
            }
        }
    }
});

describe("Access request route errors", () => {
    it("uses a localized error category and request reference instead of the API message", () => {
        const error = Object.assign(new Error("Database server internal-host-01 is unavailable."), {
            code: "CONCURRENT_MODIFICATION",
            requestId: "request-42",
            status: 409
        });

        render(
            <I18nextProvider i18n={i18n}>
                <LoadError error={error} onRetry={vi.fn().mockResolvedValue(undefined)} />
            </I18nextProvider>
        );

        const alert = screen.getByRole("alert");
        expect(alert).toHaveTextContent("Unable to load access requests.");
        expect(alert).toHaveTextContent("The request has changed. Refresh the page and try again.");
        expect(alert).toHaveTextContent("Reference: request-42");
        expect(alert).not.toHaveTextContent("Database server internal-host-01 is unavailable.");
    });
});
