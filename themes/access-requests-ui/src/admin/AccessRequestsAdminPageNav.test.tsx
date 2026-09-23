import { createInstance } from "i18next";
import { render, screen, waitFor } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
    capabilities: vi.fn()
}));

vi.mock("./api/useEntitlementsAdminApi", () => ({
    useEntitlementsAdminApi: () => ({ capabilities: mocks.capabilities })
}));

vi.mock("@keycloak/keycloak-admin-ui", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@keycloak/keycloak-admin-ui")>();

    return {
        ...actual,
        routes: [],
        useAccess: () => ({
            hasAccess: () => false,
            hasSomeAccess: () => false
        }),
        useEnvironment: () => ({ environment: { masterRealm: "master", realm: "master" } }),
        useRealm: () => ({ realm: "master", realmRepresentation: {} }),
        useServerInfo: () => ({ features: [] })
    };
});

import { AccessRequestsAdminPageNav } from "./AccessRequestsAdminPageNav";

const i18n = createInstance();

await i18n.init({
    initImmediate: false,
    lng: "en",
    resources: {
        en: {
            translation: {
                accessRequestsAdminCatalog: "Access requests",
                accessRequestsAdminFailedProvisioning: "Failed provisioning",
                accessRequestsAdminNotificationDelivery: "Notification delivery",
                configure: "Configure",
                currentRealm: "Current realm"
            }
        }
    }
});

function renderNavigation() {
    return render(
        <MemoryRouter>
            <I18nextProvider i18n={i18n}>
                <AccessRequestsAdminPageNav />
            </I18nextProvider>
        </MemoryRouter>
    );
}

describe("Access Request Admin Console navigation", () => {
    beforeEach(() => {
        mocks.capabilities.mockReset();
    });

    it("shows each administrative entry only after the server grants its capability", async () => {
        mocks.capabilities.mockResolvedValue({
            canManageCatalog: true,
            canManageNotifications: true,
            canManageProvisioningFailures: true
        });

        renderNavigation();

        expect(await screen.findByRole("link", { name: "Access requests" })).toHaveAttribute(
            "href", "/master/access-requests"
        );
        expect(screen.getByRole("region", { name: "Configure" })).toContainElement(
            screen.getByRole("link", { name: "Access requests" })
        );
        expect(screen.getByRole("link", { name: "Notification delivery" })).toHaveAttribute(
            "href", "/master/access-requests/notification-deliveries"
        );
        expect(screen.getByRole("link", { name: "Failed provisioning" })).toHaveAttribute(
            "href", "/master/access-requests/provisioning-failures"
        );
    });

    it("fails closed and does not expose the catalog entry when capability lookup is denied", async () => {
        mocks.capabilities.mockRejectedValue(new Error("Forbidden"));

        renderNavigation();

        await waitFor(() => expect(mocks.capabilities).toHaveBeenCalledOnce());
        expect(screen.queryByRole("link", { name: "Access requests" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "Failed provisioning" })).not.toBeInTheDocument();
    });
});
