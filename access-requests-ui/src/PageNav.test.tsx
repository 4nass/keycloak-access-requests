import { createInstance } from "i18next";
import { render, screen, waitFor } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
    capabilities: vi.fn()
}));

vi.mock("./api/useAccessRequestsApi", () => ({
    useAccessRequestsApi: () => ({ capabilities: mocks.capabilities })
}));

import { PageNav } from "./PageNav";

const i18n = createInstance();

await i18n.init({
    initImmediate: false,
    lng: "en",
    resources: {
        en: {
            translation: {
                accessRequestsApprovals: "Approvals",
                accessRequestsMyRequests: "My Requests",
                accessRequestsNav: "Access",
                accessRequestsRequestAccess: "Request access",
                accountManagement: "Account management",
                applications: "Applications",
                deviceActivity: "Device activity",
                groups: "Groups",
                linkedAccounts: "Linked accounts",
                personalInfo: "Personal information",
                resources: "Resources",
                signingIn: "Signing in"
            }
        }
    }
});

function renderPageNav() {
    return render(
        <MemoryRouter>
            <I18nextProvider i18n={i18n}>
                <PageNav />
            </I18nextProvider>
        </MemoryRouter>
    );
}

describe("Access Request Account Console navigation", () => {
    beforeEach(() => {
        mocks.capabilities.mockReset();
    });

    it("shows Approvals to an approver even when the approval queue is empty", async () => {
        mocks.capabilities.mockResolvedValue({ canApprove: true });

        renderPageNav();

        await waitFor(() => expect(screen.getByRole("link", { name: "Approvals" })).toBeVisible());
    });

    it("fails closed and hides Approvals from non-approvers or when capability lookup fails", async () => {
        mocks.capabilities.mockRejectedValue(new Error("Unavailable"));

        renderPageNav();

        await waitFor(() => expect(mocks.capabilities).toHaveBeenCalledOnce());
        expect(screen.queryByRole("link", { name: "Approvals" })).not.toBeInTheDocument();
    });
});
