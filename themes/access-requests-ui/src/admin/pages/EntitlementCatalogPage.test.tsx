import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
    create: vi.fn(),
    list: vi.fn(),
    references: vi.fn(),
    update: vi.fn()
}));

vi.mock("../api/useEntitlementsAdminApi", () => ({
    useEntitlementsAdminApi: () => api
}));

import { EntitlementCatalogPage } from "./EntitlementCatalogPage";

const entitlement = {
    approverRoleId: "finance-approvers",
    createdAt: "2026-09-04T10:00:00Z",
    description: "Read-only finance access",
    displayName: "Finance Reader",
    id: "finance-reader",
    requestable: true,
    resourceId: "finance-reader-role",
    resourceType: "CLIENT_ROLE" as const,
    riskLevel: "LOW" as const,
    updatedAt: "2026-09-04T10:00:00Z",
    version: 4
};

describe("EntitlementCatalogPage", () => {
    beforeEach(() => {
        api.create.mockReset();
        api.list.mockReset().mockResolvedValue({ items: [entitlement], page: 0, size: 20, total: 1 });
        api.references.mockReset().mockImplementation((type) => Promise.resolve(type === "REALM_ROLE"
            ? [
                { description: "Access to finance reports", id: "finance-reader-role", name: "Finance Reader", type },
                { description: "Approves finance access", id: "finance-approvers", name: "Finance Approvers", type }
            ]
            : []));
        api.update.mockReset().mockResolvedValue({ ...entitlement, requestable: false, version: 5 });
    });

    it("offers Events as an Access requests sub-tab instead of another sidebar section", async () => {
        render(<MemoryRouter initialEntries={["/master/access-requests"]}>
            <EntitlementCatalogPage />
        </MemoryRouter>);

        expect(await screen.findByRole("tab", { name: "accessRequestsAdminCatalog" })).toHaveAttribute(
            "aria-selected", "true"
        );
        expect(screen.getByRole("tab", { name: "accessRequestsAdminEvents" })).toHaveAttribute(
            "href", "/master/access-requests/events"
        );
    });

    it("renders the complete administrative metadata using native list affordances", async () => {
        render(<EntitlementCatalogPage />);

        expect(await screen.findByRole("heading", { name: "Finance Reader" })).toBeVisible();
        expect(screen.getByText("accessRequestsAdminResourceTypeClientRole: finance-reader-role")).toBeVisible();
        expect(screen.getByText("finance-approvers")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminRiskLevelLow")).toBeVisible();
        expect(screen.getByText("accessRequestsAdminOpenToRequests")).toBeVisible();
        expect(api.list).toHaveBeenCalledWith({ page: 0, size: 20 });
    });

    it("labels a non-requestable entitlement as closed to requests", async () => {
        api.list.mockResolvedValue({
            items: [{ ...entitlement, requestable: false }], page: 0, size: 20, total: 1
        });
        render(<EntitlementCatalogPage />);

        expect(await screen.findByText("accessRequestsAdminClosedToRequests")).toBeVisible();
        expect(screen.queryByText("accessRequestsAdminOpenToRequests")).not.toBeInTheDocument();
    });

    it("edits requestability with the current optimistic lock version", async () => {
        const user = userEvent.setup();
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEditEntitlement" }));
        const requestable = screen.getByRole("checkbox", { name: "accessRequestsAdminRequestable" });
        await user.click(requestable);
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminSave" }));

        await waitFor(() => expect(api.update).toHaveBeenCalledWith("finance-reader", {
            approverRoleId: "finance-approvers",
            description: "Read-only finance access",
            displayName: "Finance Reader",
            requestable: false,
            riskLevel: "LOW",
            version: 4
        }));
        await waitFor(() => expect(screen.getByText("accessRequestsAdminUpdated")).toBeVisible());
    });

    it("selects immutable Keycloak resources and approver roles instead of accepting raw identifiers", async () => {
        const user = userEvent.setup();
        api.create.mockResolvedValue({ ...entitlement, id: "finance-reader" });
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminCreateEntitlement" }));
        await waitFor(() => expect(api.references).toHaveBeenCalled());
        await user.selectOptions(
            screen.getByRole("combobox", { name: "accessRequestsAdminSelectResource" }),
            "finance-reader-role"
        );
        await user.selectOptions(
            screen.getByRole("combobox", { name: "accessRequestsAdminSelectApproverRole" }),
            "finance-approvers"
        );
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminDisplayName" }), "Finance reader access");
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminDescription" }), "Read-only finance access.");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminSave" }));

        await waitFor(() => expect(api.create).toHaveBeenCalledWith({
            approverRoleId: "finance-approvers",
            description: "Read-only finance access.",
            displayName: "Finance reader access",
            resourceId: "finance-reader-role",
            resourceType: "REALM_ROLE",
            riskLevel: "LOW"
        }));
        expect(screen.queryByRole("textbox", { name: "accessRequestsAdminResourceId" })).not.toBeInTheDocument();
        expect(screen.queryByRole("textbox", { name: "accessRequestsAdminApproverRole" })).not.toBeInTheDocument();
    });

    it("retains the existing page and shows a safe inline error when refresh fails", async () => {
        const user = userEvent.setup();
        const failure = Object.assign(new Error("The access request API call failed."), {
            code: "HTTP_503",
            requestId: "request-42",
            status: 503
        });
        api.list.mockResolvedValueOnce({ items: [entitlement], page: 0, size: 20, total: 1 }).mockRejectedValueOnce(failure);
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEditEntitlement" }));
        await user.click(screen.getByRole("checkbox", { name: "accessRequestsAdminRequestable" }));
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminSave" }));

        expect(await screen.findByText("accessRequestsAdminErrorUnavailable (request-42)")).toBeVisible();
        expect(screen.getByRole("heading", { name: "Finance Reader" })).toBeVisible();
    });
});
