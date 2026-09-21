import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
    capabilities: vi.fn(),
    create: vi.fn(),
    list: vi.fn(),
    update: vi.fn()
}));

vi.mock("../api/useEntitlementsAdminApi", () => ({
    useEntitlementsAdminApi: () => api
}));

import { EntitlementCatalogRoute } from "./EntitlementCatalogRoute";

describe("EntitlementCatalogRoute", () => {
    beforeEach(() => {
        api.capabilities.mockReset().mockResolvedValue({ canManageCatalog: true });
        api.create.mockReset();
        api.list.mockReset().mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
        api.update.mockReset();
    });

    it("renders catalog controls only after the server grants catalog management", async () => {
        render(<EntitlementCatalogRoute />);

        expect(await screen.findByRole("button", { name: "accessRequestsAdminCreateEntitlement" })).toBeVisible();
        expect(api.capabilities).toHaveBeenCalledOnce();
    });

    it("does not render catalog write controls when the server denies the capability", async () => {
        api.capabilities.mockResolvedValue({ canManageCatalog: false });

        render(<EntitlementCatalogRoute />);

        expect(await screen.findByRole("heading", { name: "accessRequestsAdminErrorForbidden" })).toBeVisible();
        expect(screen.queryByRole("button", { name: "accessRequestsAdminCreateEntitlement" })).not.toBeInTheDocument();
        expect(api.list).not.toHaveBeenCalled();
    });

    it("treats a forbidden capability response as access denial without exposing the API error", async () => {
        api.capabilities.mockRejectedValue(Object.assign(new Error("The access request API call failed."), {
            code: "HTTP_403",
            requestId: "request-42",
            status: 403
        }));

        render(<EntitlementCatalogRoute />);

        expect(await screen.findByRole("heading", { name: "accessRequestsAdminErrorForbidden" })).toBeVisible();
        expect(screen.queryByText(/request-42/)).not.toBeInTheDocument();
    });
});
