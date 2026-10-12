import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ capabilities: vi.fn() }));
vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => api }));

import { AccessRequestsAdminTabs } from "./AccessRequestsAdminTabs";

function CurrentLocation() {
    return <output>{useLocation().pathname}</output>;
}

function renderTabs(active: "catalog" | "assurance" | "events" | "incidents" | "notifications" | "provisioning" | "revocations",
    realm = "master") {
    return render(<MemoryRouter initialEntries={[`/${realm}/access-requests/events`]}>
        <Routes><Route path="/:realm/access-requests/*" element={<>
            <AccessRequestsAdminTabs active={active} />
            <CurrentLocation />
        </>} /></Routes>
    </MemoryRouter>);
}

describe("Access requests Admin tabs", () => {
    beforeEach(() => {
        api.capabilities.mockReset();
    });

    it("shows realm-scoped tabs, including policy settings only for realm admins", async () => {
        api.capabilities.mockResolvedValue({
            canManageCatalog: true, canViewEvents: true, canManageNotifications: true, canManageProvisioningFailures: true,
            canManageAssurancePolicy: true
        });
        renderTabs("events");

        expect(await screen.findByRole("tab", { name: "accessRequestsAdminCatalogTab" }))
            .toHaveAttribute("href", "/master/access-requests");
        expect(screen.getByRole("tab", { name: "accessRequestsAdminEvents" })).toHaveAttribute("aria-selected", "true");
        expect(screen.getByRole("tab", { name: "accessRequestsAdminAssurancePolicy" }))
            .toHaveAttribute("href", "/master/access-requests/assurance-policy");
        expect(screen.getByRole("tab", { name: "accessRequestsAdminIncidents" }))
            .toHaveAttribute("href", "/master/access-requests/incidents");
        expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
            "accessRequestsAdminCatalogTab", "accessRequestsAdminAssurancePolicy",
            "accessRequestsAdminIncidents",
            "accessRequestsAdminEvents"
        ]);

        await userEvent.click(screen.getByRole("tab", { name: "accessRequestsAdminIncidents" }));
        expect(screen.getByText("/master/access-requests/incidents")).toBeVisible();
    });

    it("hides tabs the server has not authorized", async () => {
        api.capabilities.mockResolvedValue({
            canManageCatalog: false, canViewEvents: true, canManageNotifications: false, canManageProvisioningFailures: true
        });
        renderTabs("provisioning");

        await waitFor(() => expect(screen.getByRole("tab", { name: "accessRequestsAdminIncidents" })).not.toHaveAttribute("aria-disabled", "true"));
        expect(screen.queryByRole("tab", { name: "accessRequestsAdminCatalogTab" })).not.toBeInTheDocument();
        expect(screen.getByRole("tab", { name: "accessRequestsAdminEvents" })).toBeVisible();
        expect(screen.getByRole("tab", { name: "accessRequestsAdminIncidents" })).toHaveAttribute("aria-selected", "true");
        expect(screen.queryByRole("tab", { name: "accessRequestsAdminNotificationDelivery" })).not.toBeInTheDocument();
    });

    it("keeps the complete realm name when it contains access-requests", async () => {
        api.capabilities.mockResolvedValue({
            canManageCatalog: true, canViewEvents: true, canManageNotifications: true, canManageProvisioningFailures: true,
            canManageAssurancePolicy: true
        });
        renderTabs("events", "access-requests-demo");

        const destinations = [
            ["accessRequestsAdminCatalogTab", ""],
            ["accessRequestsAdminAssurancePolicy", "/assurance-policy"],
            ["accessRequestsAdminIncidents", "/incidents"],
            ["accessRequestsAdminEvents", "/events"]
        ];

        for (const [name, suffix] of destinations) {
            const expected = `/access-requests-demo/access-requests${suffix}`;
            const tab = await screen.findByRole("tab", { name });
            expect(tab).toHaveAttribute("href", expected);
            await userEvent.click(tab);
            expect(screen.getByText(expected)).toBeVisible();
        }
    });

    it("keeps only the current tab visible and disabled when capability lookup fails", async () => {
        api.capabilities.mockRejectedValue(new Error("unavailable"));
        renderTabs("events");
        expect(await screen.findByText("/master/access-requests/events")).toBeVisible();
        expect(screen.getByRole("tab", { name: "accessRequestsAdminEvents" })).toHaveAttribute("aria-disabled", "true");
        expect(screen.getAllByRole("tab")).toHaveLength(1);
    });
});
