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

function renderTabs(active: "catalog" | "assurance" | "events" | "notifications" | "provisioning" | "revocations") {
    return render(<MemoryRouter initialEntries={["/master/access-requests/events"]}>
        <Routes><Route path="/master/access-requests/*" element={<>
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
        expect(screen.getByRole("tab", { name: "accessRequestsAdminNotificationDelivery" }))
            .toHaveAttribute("href", "/master/access-requests/notification-deliveries");
        expect(screen.getByRole("tab", { name: "accessRequestsAdminFailedProvisioning" }))
            .toHaveAttribute("href", "/master/access-requests/provisioning-failures");
        expect(screen.getByRole("tab", { name: "accessRequestsAdminRevocationFailures" }))
            .toHaveAttribute("href", "/master/access-requests/revocation-failures");
        expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
            "accessRequestsAdminCatalogTab", "accessRequestsAdminAssurancePolicy",
            "accessRequestsAdminNotificationDelivery",
            "accessRequestsAdminFailedProvisioning", "accessRequestsAdminRevocationFailures",
            "accessRequestsAdminEvents"
        ]);

        await userEvent.click(screen.getByRole("tab", { name: "accessRequestsAdminNotificationDelivery" }));
        expect(screen.getByText("/master/access-requests/notification-deliveries")).toBeVisible();
    });

    it("hides tabs the server has not authorized", async () => {
        api.capabilities.mockResolvedValue({
            canManageCatalog: false, canViewEvents: true, canManageNotifications: false, canManageProvisioningFailures: true
        });
        renderTabs("provisioning");

        await waitFor(() => expect(screen.getByRole("tab", { name: "accessRequestsAdminFailedProvisioning" })).not.toHaveAttribute("aria-disabled", "true"));
        expect(screen.queryByRole("tab", { name: "accessRequestsAdminCatalogTab" })).not.toBeInTheDocument();
        expect(screen.getByRole("tab", { name: "accessRequestsAdminEvents" })).toBeVisible();
        expect(screen.queryByRole("tab", { name: "accessRequestsAdminNotificationDelivery" })).not.toBeInTheDocument();
        expect(screen.getByRole("tab", { name: "accessRequestsAdminRevocationFailures" })).toBeVisible();
    });

    it("keeps only the current tab visible and disabled when capability lookup fails", async () => {
        api.capabilities.mockRejectedValue(new Error("unavailable"));
        renderTabs("events");
        expect(await screen.findByText("/master/access-requests/events")).toBeVisible();
        expect(screen.getByRole("tab", { name: "accessRequestsAdminEvents" })).toHaveAttribute("aria-disabled", "true");
        expect(screen.getAllByRole("tab")).toHaveLength(1);
    });
});
