import { createInstance } from "i18next";
import { render, screen, waitFor } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
    capabilities: vi.fn(),
    features: {
        isLinkedAccountsEnabled: true,
        isViewApplicationsEnabled: true,
        isViewGroupsEnabled: true,
        isMyResourcesEnabled: false
    }
}));

vi.mock("@keycloak/keycloak-account-ui", () => ({
    useEnvironment: () => ({ environment: { features: mocks.features } })
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
                accessRequestsCatalog: "Catalog",
                accessRequestsMyRequests: "My requests",
                accessRequestsNav: "Access requests",
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

function renderPageNav(initialEntry = "/request-access") {
    return render(
        <MemoryRouter initialEntries={[initialEntry]}>
            <I18nextProvider i18n={i18n}>
                <PageNav />
            </I18nextProvider>
        </MemoryRouter>
    );
}

describe("Access Request Account Console navigation", () => {
    beforeEach(() => {
        mocks.capabilities.mockReset();
        mocks.features.isLinkedAccountsEnabled = true;
        mocks.features.isViewApplicationsEnabled = true;
        mocks.features.isViewGroupsEnabled = true;
        mocks.features.isMyResourcesEnabled = false;
    });

    it("shows Approvals to an approver even when the approval queue is empty", async () => {
        mocks.capabilities.mockResolvedValue({ canApprove: true });

        renderPageNav("/approvals");

        await waitFor(() => expect(screen.getByRole("link", { name: "Approvals" })).toBeVisible());
    });

    it("fails closed and hides Approvals from non-approvers or when capability lookup fails", async () => {
        mocks.capabilities.mockRejectedValue(new Error("Unavailable"));

        renderPageNav();

        await waitFor(() => expect(mocks.capabilities).toHaveBeenCalledOnce());
        expect(screen.queryByRole("link", { name: "Approvals" })).not.toBeInTheDocument();
    });

    it("renders Access requests as an expanded native navigation group for an active route", async () => {
        mocks.capabilities.mockResolvedValue({ canApprove: false });

        renderPageNav();

        const group = screen.getByRole("button", { name: "Access requests" });
        expect(group).toHaveAttribute("aria-expanded", "true");

        const navigation = screen.getByRole("navigation", { name: "Account management" });
        expect(screen.getAllByRole("navigation")).toHaveLength(1);
        expect(navigation).toContainElement(group);
        const catalog = screen.getByRole("link", { name: "Catalog" });
        expect(navigation).toContainElement(catalog);
        expect(catalog).toHaveAttribute("aria-current", "page");
        expect(navigation).toContainElement(screen.getByRole("link", { name: "My requests" }));
    });

    it("collapses Access requests when another account page is active", async () => {
        mocks.capabilities.mockResolvedValue({ canApprove: false });

        renderPageNav("/personal-info");

        expect(screen.getByRole("button", { name: "Access requests" })).toHaveAttribute("aria-expanded", "false");
    });

    it("hides native pages disabled by the realm instead of linking to forbidden APIs", () => {
        mocks.capabilities.mockResolvedValue({ canApprove: false });
        mocks.features.isLinkedAccountsEnabled = false;
        mocks.features.isViewApplicationsEnabled = false;
        mocks.features.isViewGroupsEnabled = false;

        renderPageNav();

        expect(screen.queryByRole("link", { name: "Linked accounts" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "Applications" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "Groups" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "Resources" })).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Personal information" })).toBeVisible();
    });

    it("shows native resource sharing only when Keycloak enables it", () => {
        mocks.capabilities.mockResolvedValue({ canApprove: false });
        mocks.features.isMyResourcesEnabled = true;

        renderPageNav();

        expect(screen.getByRole("link", { name: "Resources" })).toBeVisible();
    });
});
