import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

const api = vi.hoisted(() => ({ capabilities: vi.fn(), revocationFailures: vi.fn() }));
vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => api }));

import { RevocationFailuresRoute } from "./RevocationFailuresRoute";

describe("RevocationFailuresRoute", () => {
    beforeEach(() => {
        api.capabilities.mockReset().mockResolvedValue({ canManageProvisioningFailures: true });
        api.revocationFailures.mockReset().mockResolvedValue({ items: [], page: 0, size: 20, total: 0 });
    });

    it("opens the page only after the server grants the management capability", async () => {
        render(<MemoryRouter initialEntries={["/master/access-requests/revocation-failures"]}>
            <RevocationFailuresRoute />
        </MemoryRouter>);
        expect(await screen.findByRole("heading", { name: "accessRequestsAdminRevocationFailures" })).toBeVisible();
        await waitFor(() => expect(api.revocationFailures).toHaveBeenCalledWith({
            page: 0, size: 20, state: "OPEN"
        }));
    });

    it("does not load incidents when the server denies access", async () => {
        api.capabilities.mockResolvedValue({ canManageProvisioningFailures: false });
        render(<RevocationFailuresRoute />);
        expect(await screen.findByRole("heading", { name: "accessRequestsAdminErrorForbidden" })).toBeVisible();
        expect(api.revocationFailures).not.toHaveBeenCalled();
    });

    it("does not reveal details from a forbidden capability response", async () => {
        api.capabilities.mockRejectedValue(Object.assign(new Error("API call failed"), {
            code: "HTTP_403", requestId: "hidden-request", status: 403
        }));
        render(<RevocationFailuresRoute />);
        expect(await screen.findByRole("heading", { name: "accessRequestsAdminErrorForbidden" })).toBeVisible();
        expect(screen.queryByText(/hidden-request/)).not.toBeInTheDocument();
        expect(api.revocationFailures).not.toHaveBeenCalled();
    });
});
