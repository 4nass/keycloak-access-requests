import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
    capabilities: vi.fn(),
    approvalAssurancePolicy: vi.fn(),
    updateApprovalAssurancePolicy: vi.fn()
}));
vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => api }));

import { ApprovalAssurancePolicyRoute } from "./ApprovalAssurancePolicyRoute";

describe("ApprovalAssurancePolicyRoute", () => {
    beforeEach(() => {
        api.capabilities.mockReset().mockResolvedValue({ canManageAssurancePolicy: true });
        api.approvalAssurancePolicy.mockReset().mockResolvedValue({
            high: { acr: "2", loa: 2, maxAgeSeconds: 1800 },
            critical: { acr: "2", loa: 2, maxAgeSeconds: 300 }
        });
        api.updateApprovalAssurancePolicy.mockReset().mockImplementation(async (policy) => policy);
    });

    it("places the page heading above the tabs and groups each risk policy", async () => {
        render(<MemoryRouter initialEntries={["/master/access-requests/assurance-policy"]}>
            <ApprovalAssurancePolicyRoute />
        </MemoryRouter>);

        const heading = screen.getByRole("heading", { level: 1, name: "accessRequestsAdminAssurancePolicy" });
        const tab = await screen.findByRole("tab", { name: "accessRequestsAdminAssurancePolicy" });
        expect(heading.compareDocumentPosition(tab) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        expect(heading.closest(".pf-v5-c-page__main-section")).toHaveClass("pf-m-light");

        const high = screen.getByRole("region", { name: "accessRequestsAdminRiskLevelHigh" });
        const critical = screen.getByRole("region", { name: "accessRequestsAdminRiskLevelCritical" });
        expect(within(high).getByRole("textbox", { name: "accessRequestsAdminAssuranceAcr" })).toHaveValue("2");
        expect(within(critical).getByRole("spinbutton", { name: "accessRequestsAdminAssuranceAgeSeconds" }))
            .toHaveValue(300);
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
