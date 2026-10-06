import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
    approvalAssurancePolicy: vi.fn(),
    updateApprovalAssurancePolicy: vi.fn()
}));
vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => api }));

import { ApprovalAssurancePolicyRoute } from "./ApprovalAssurancePolicyRoute";

describe("ApprovalAssurancePolicyRoute", () => {
    beforeEach(() => {
        api.approvalAssurancePolicy.mockReset().mockResolvedValue({
            high: { acr: "2", loa: 2, maxAgeSeconds: 1800 },
            critical: { acr: "2", loa: 2, maxAgeSeconds: 300 }
        });
        api.updateApprovalAssurancePolicy.mockReset().mockImplementation(async (policy) => policy);
    });

    it("loads and saves the per-realm assurance limits", async () => {
        const user = userEvent.setup();
        render(<ApprovalAssurancePolicyRoute />);

        const criticalAge = await screen.findByDisplayValue("300");
        expect(screen.getByText("accessRequestsAdminAssuranceMaxAgeWarning")).toBeVisible();
        await user.clear(criticalAge);
        await user.type(criticalAge, "120");
        await user.click(screen.getByRole("button", { name: "save" }));

        expect(api.updateApprovalAssurancePolicy).toHaveBeenCalledWith({
            high: { acr: "2", loa: 2, maxAgeSeconds: 1800 },
            critical: { acr: "2", loa: 2, maxAgeSeconds: 120 }
        });
        expect(await screen.findByText("accessRequestsAdminAssuranceSaved")).toBeVisible();
    });

    it("does not expose controls when policy read is forbidden", async () => {
        api.approvalAssurancePolicy.mockRejectedValue(Object.assign(new Error("Forbidden"), {
            status: 403, code: "HTTP_403"
        }));
        render(<ApprovalAssurancePolicyRoute />);

        expect(await screen.findByText("accessRequestsAdminErrorForbidden")).toBeVisible();
        expect(screen.queryByRole("button", { name: "save" })).not.toBeInTheDocument();
    });
});
