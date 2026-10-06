import { act, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
    capabilities: vi.fn(),
    failedProvisioningRequests: vi.fn(),
    retryFailedProvisioning: vi.fn()
}));

vi.mock("../api/useEntitlementsAdminApi", () => ({
    useEntitlementsAdminApi: () => api
}));

import { FailedProvisioningRoute } from "./FailedProvisioningRoute";

describe("FailedProvisioningRoute", () => {
    beforeEach(() => {
        api.capabilities.mockReset().mockResolvedValue({ canManageProvisioningFailures: true });
        api.failedProvisioningRequests.mockReset().mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
        api.retryFailedProvisioning.mockReset();
    });

    it("renders the operational page only after the server grants its capability", async () => {
        render(<FailedProvisioningRoute />);

        await waitFor(() => expect(api.failedProvisioningRequests).toHaveBeenCalledWith({ page: 0, size: 20, state: "OPEN" }));
        expect(screen.getByRole("heading", { name: "accessRequestsAdminFailedProvisioning" })).toBeVisible();
        expect(api.capabilities).toHaveBeenCalledOnce();
    });

    it("fails closed when the server denies provisioning-failure management", async () => {
        api.capabilities.mockResolvedValue({ canManageProvisioningFailures: false });

        render(<FailedProvisioningRoute />);

        expect(await screen.findByRole("heading", { name: "accessRequestsAdminErrorForbidden" })).toBeVisible();
        expect(api.failedProvisioningRequests).not.toHaveBeenCalled();
    });

    it("keeps the native heading and a non-actionable tab during capability loading and denial", async () => {
        let finish!: (value: { canManageProvisioningFailures: boolean }) => void;
        api.capabilities.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
        render(<MemoryRouter initialEntries={["/master/access-requests/provisioning-failures"]}>
            <FailedProvisioningRoute />
        </MemoryRouter>);

        expect(screen.getByRole("heading", { level: 1, name: "accessRequestsAdminFailedProvisioning" })).toBeVisible();
        expect(screen.getByRole("tab", { name: "accessRequestsAdminFailedProvisioning" })).toBeDisabled();
        await act(async () => finish({ canManageProvisioningFailures: false }));
        expect(await screen.findByRole("heading", { name: "accessRequestsAdminErrorForbidden" })).toBeVisible();
        expect(screen.getByRole("heading", { level: 1, name: "accessRequestsAdminFailedProvisioning" })).toBeVisible();
        expect(screen.getByRole("tab", { name: "accessRequestsAdminFailedProvisioning" })).toBeDisabled();
        expect(api.failedProvisioningRequests).not.toHaveBeenCalled();
    });

    it("treats a forbidden capability response as access denial without showing API details", async () => {
        api.capabilities.mockRejectedValue(Object.assign(new Error("The access request API call failed."), {
            code: "HTTP_403",
            requestId: "request-42",
            status: 403
        }));

        render(<FailedProvisioningRoute />);

        expect(await screen.findByRole("heading", { name: "accessRequestsAdminErrorForbidden" })).toBeVisible();
        expect(screen.queryByText(/request-42/)).not.toBeInTheDocument();
        expect(api.failedProvisioningRequests).not.toHaveBeenCalled();
    });
});
