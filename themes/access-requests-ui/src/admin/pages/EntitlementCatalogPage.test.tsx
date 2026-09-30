import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
    create: vi.fn(),
    createAccessPackage: vi.fn(),
    getAccessPackage: vi.fn(),
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
    allowPermanent: false,
    createdAt: "2026-09-04T10:00:00Z",
    defaultDurationSeconds: 2_592_000,
    description: "Read-only finance access",
    displayName: "Finance Reader",
    id: "finance-reader",
    maxDurationSeconds: 7_776_000,
    requestable: true,
    resourceId: "finance-reader-role",
    resourceType: "CLIENT_ROLE" as const,
    riskLevel: "LOW" as const,
    updatedAt: "2026-09-04T10:00:00Z",
    version: 4
};

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (reason?: unknown) => void;
    const promise = new Promise<T>((onResolve, onReject) => {
        resolve = onResolve;
        reject = onReject;
    });
    return { promise, resolve, reject };
}

describe("EntitlementCatalogPage", () => {
    beforeEach(() => {
        api.create.mockReset();
        api.createAccessPackage.mockReset();
        api.getAccessPackage.mockReset().mockResolvedValue(null);
        api.list.mockReset().mockResolvedValue({ items: [entitlement], page: 0, size: 20, total: 1 });
        api.references.mockReset().mockImplementation((type) => Promise.resolve({
            items: type === "REALM_ROLE" ? [
                { description: "Access to finance reports", id: "finance-reader-role", name: "Finance Reader", type },
                { description: "Approves finance access", id: "finance-approvers", name: "Finance Approvers", type }
            ] : type === "CLIENT_ROLE" ? [
                { description: "Package client role", id: "client-role-id", name: "Client Role", type }
            ] : [],
            nextFirst: 2,
            hasMore: false
        }));
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
            allowPermanent: false,
            defaultDurationSeconds: 2_592_000,
            description: "Read-only finance access",
            displayName: "Finance Reader",
            maxDurationSeconds: 7_776_000,
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
        expect(api.references).not.toHaveBeenCalled();
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminSearchResources" }), "finance");
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminSearchApproverRoles" }), "finance");
        await waitFor(() => expect(api.references).toHaveBeenCalledWith("REALM_ROLE",
            expect.objectContaining({ search: "finance" })));
        await user.selectOptions(
            screen.getByRole("combobox", { name: "accessRequestsAdminSelectResource" }),
            "finance-reader-role"
        );
        await waitFor(() => expect(screen.getByRole("combobox", { name: "accessRequestsAdminSelectApproverRole" }))
            .toHaveTextContent("Finance Approvers"));
        await user.selectOptions(
            screen.getByRole("combobox", { name: "accessRequestsAdminSelectApproverRole" }),
            "finance-approvers"
        );
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminDisplayName" }), "Finance reader access");
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminDescription" }), "Read-only finance access.");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminSave" }));

        await waitFor(() => expect(api.create).toHaveBeenCalledWith({
            approverRoleId: "finance-approvers",
            allowPermanent: false,
            defaultDurationSeconds: 2_592_000,
            description: "Read-only finance access.",
            displayName: "Finance reader access",
            maxDurationSeconds: 7_776_000,
            resourceId: "finance-reader-role",
            resourceType: "REALM_ROLE",
            riskLevel: "LOW"
        }));
        expect(screen.queryByRole("textbox", { name: "accessRequestsAdminResourceId" })).not.toBeInTheDocument();
        expect(screen.queryByRole("textbox", { name: "accessRequestsAdminApproverRole" })).not.toBeInTheDocument();
    });

    it("creates a closed access package from selected Keycloak roles and shows its group before publishing", async () => {
        const user = userEvent.setup();
        const created = {
            ...entitlement, id: "access-package-id", displayName: "Temporary access",
            resourceType: "GROUP" as const, resourceId: "jit-group-id", requestable: false, version: 0
        };
        api.createAccessPackage.mockResolvedValue(created);
        api.getAccessPackage.mockResolvedValue({
            entitlementId: created.id, groupId: "jit-group-id", groupName: "AR_PKG_TEMPORARY_ACCESS",
            groupExists: true, configurationValid: true,
            roleMappings: [
                { type: "REALM_ROLE", roleId: "finance-reader-role", name: "Finance Reader", missing: false },
                { type: "CLIENT_ROLE", roleId: "client-role-id", name: "Client Role", missing: false }
            ]
        });
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminPackageCreate" }));
        const dialog = within(screen.getByRole("dialog", { name: "accessRequestsAdminPackageCreate" }));
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminDisplayName" }), "Temporary access");
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminDescription" }), "Temporary package");
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminSearchApproverRoles" }), "finance");
        await waitFor(() => expect(screen.getByRole("combobox", { name: "accessRequestsAdminSelectApproverRole" }))
            .toHaveTextContent("Finance Approvers"));
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminSelectApproverRole" }), "finance-approvers");
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminPackageSearchRoles" }), "finance");
        await waitFor(() => expect(screen.getByRole("combobox", { name: "accessRequestsAdminPackageSelectRole" }))
            .toHaveTextContent("Finance Reader"));
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminPackageSelectRole" }), "finance-reader-role");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminPackageAddRole" }));
        expect(screen.getByRole("list", { name: "accessRequestsAdminPackageSelectedRoles" })).toHaveTextContent("Finance Reader");
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminPackageRoles" }), "CLIENT_ROLE");
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminPackageSearchRoles" }), "client");
        await waitFor(() => expect(screen.getByRole("combobox", { name: "accessRequestsAdminPackageSelectRole" }))
            .toHaveTextContent("Client Role"));
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminPackageSelectRole" }), "client-role-id");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminPackageAddRole" }));
        expect(screen.getByRole("list", { name: "accessRequestsAdminPackageSelectedRoles" })).toHaveTextContent("Client Role");
        expect(screen.getByRole("combobox", { name: "accessRequestsAdminPackageSelectRole" })).not.toBeRequired();
        await user.click(dialog.getByRole("button", { name: "accessRequestsAdminPackageCreate" }));

        await waitFor(() => expect(api.createAccessPackage).toHaveBeenCalledWith(expect.objectContaining({
            displayName: "Temporary access",
            roleMappings: [
                { type: "REALM_ROLE", roleId: "finance-reader-role" },
                { type: "CLIENT_ROLE", roleId: "client-role-id" }
            ]
        })));
        await waitFor(() => expect(screen.getByText("AR_PKG_TEMPORARY_ACCESS")).toBeVisible());
        expect(screen.getByRole("checkbox", { name: "accessRequestsAdminRequestable" })).not.toBeChecked();
        expect(screen.getByText("accessRequestsAdminPackageCreated")).toBeVisible();
    });

    it("blocks publication when the JIT group configuration has changed", async () => {
        const user = userEvent.setup();
        api.list.mockResolvedValue({
            items: [{ ...entitlement, id: "jit-id", resourceId: "jit-group", resourceType: "GROUP", requestable: false }],
            page: 0, size: 20, total: 1
        });
        api.getAccessPackage.mockResolvedValue({
            entitlementId: "jit-id", groupId: "jit-group", groupName: "AR_PKG_JIT", groupExists: true,
            configurationValid: false,
            roleMappings: [{ type: "REALM_ROLE", roleId: "role-id", name: "Role", missing: false }]
        });
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEditEntitlement" }));
        expect(await screen.findByText("accessRequestsAdminPackageInvalidConfiguration")).toBeVisible();
        expect(screen.getByRole("checkbox", { name: "accessRequestsAdminRequestable" })).toBeDisabled();
    });

    it("allows an invalid published package to be closed to new requests", async () => {
        const user = userEvent.setup();
        api.list.mockResolvedValue({
            items: [{ ...entitlement, id: "package-id", resourceId: "package-group", resourceType: "GROUP", requestable: true }],
            page: 0, size: 20, total: 1
        });
        api.getAccessPackage.mockResolvedValue({
            entitlementId: "package-id", groupId: "package-group", groupName: "AR_PKG_PACKAGE",
            groupExists: true, configurationValid: false, roleMappings: []
        });
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEditEntitlement" }));
        expect(await screen.findByText("accessRequestsAdminPackageInvalidConfiguration")).toBeVisible();
        const requestable = screen.getByRole("checkbox", { name: "accessRequestsAdminRequestable" });
        expect(requestable).toBeEnabled();
        expect(requestable).toBeChecked();
        await user.click(requestable);
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminSave" }));

        await waitFor(() => expect(api.update).toHaveBeenCalledWith("package-id", expect.objectContaining({
            requestable: false
        })));
    });

    it("lets the administrator change default, maximum, and permanent access independently", async () => {
        const user = userEvent.setup();
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEditEntitlement" }));
        const defaultDuration = screen.getByRole("spinbutton", { name: "accessRequestsAdminDefaultDuration" });
        const maxDuration = screen.getByRole("spinbutton", { name: "accessRequestsAdminMaxDuration" });
        expect(defaultDuration).toHaveValue(30);
        expect(maxDuration).toHaveValue(90);
        await user.clear(defaultDuration);
        await user.type(defaultDuration, "14");
        await user.clear(maxDuration);
        await user.type(maxDuration, "60");
        await user.click(screen.getByRole("checkbox", { name: "accessRequestsAdminAllowPermanent" }));
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminSave" }));

        await waitFor(() => expect(api.update).toHaveBeenCalledWith("finance-reader", expect.objectContaining({
            allowPermanent: true,
            defaultDurationSeconds: 1_209_600,
            maxDurationSeconds: 5_184_000
        })));
    });

    it("resets duration presets when risk changes and refuses an inverted range", async () => {
        const user = userEvent.setup();
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEditEntitlement" }));
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminRiskLevel" }), "HIGH");
        const defaultDuration = screen.getByRole("spinbutton", { name: "accessRequestsAdminDefaultDuration" });
        const maxDuration = screen.getByRole("spinbutton", { name: "accessRequestsAdminMaxDuration" });
        expect(defaultDuration).toHaveValue(8);
        expect(maxDuration).toHaveValue(24);
        await user.clear(maxDuration);
        await user.type(maxDuration, "4");
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminSave" }));

        expect(api.update).not.toHaveBeenCalled();
        expect(screen.getByText("accessRequestsAdminInvalidDuration")).toBeVisible();
    });

    it("ignores an older page response that arrives after the current page", async () => {
        const oldPage = deferred<{ items: typeof entitlement[]; total: number }>();
        const currentPage = deferred<{ items: typeof entitlement[]; total: number }>();
        api.list.mockReset()
            .mockResolvedValueOnce({ items: [entitlement], total: 40 })
            .mockReturnValueOnce(oldPage.promise)
            .mockReturnValueOnce(currentPage.promise);
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        fireEvent.click(screen.getAllByRole("button", { name: "Go to next page" })[0]);
        await waitFor(() => expect(api.list).toHaveBeenCalledWith({ page: 1, size: 20 }));
        fireEvent.click(screen.getAllByRole("button", { name: "Go to previous page" })[0]);
        await waitFor(() => expect(api.list).toHaveBeenCalledTimes(3));

        await act(async () => currentPage.resolve({ items: [{ ...entitlement, displayName: "Current page" }], total: 40 }));
        expect(screen.getByRole("heading", { name: "Current page" })).toBeVisible();
        await act(async () => oldPage.resolve({ items: [{ ...entitlement, displayName: "Stale page" }], total: 40 }));
        expect(screen.getByRole("heading", { name: "Current page" })).toBeVisible();
        expect(screen.queryByRole("heading", { name: "Stale page" })).not.toBeInTheDocument();
    });

    it("hides the previous page and its edit action while the next page loads", async () => {
        const nextPage = deferred<{ items: typeof entitlement[]; total: number }>();
        api.list.mockReset()
            .mockResolvedValueOnce({ items: [entitlement], total: 40 })
            .mockReturnValueOnce(nextPage.promise);
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        fireEvent.click(screen.getAllByRole("button", { name: "Go to next page" })[0]);
        await waitFor(() => expect(api.list).toHaveBeenCalledWith({ page: 1, size: 20 }));
        expect(screen.queryByRole("heading", { name: "Finance Reader" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "accessRequestsAdminEditEntitlement" })).not.toBeInTheDocument();
        expect(screen.getByLabelText("loading")).toBeVisible();

        await act(async () => nextPage.resolve({
            items: [{ ...entitlement, displayName: "Next page" }], total: 40
        }));
        expect(screen.getByRole("heading", { name: "Next page" })).toBeVisible();
    });

    it("retains the current page during an action-triggered refresh", async () => {
        const refresh = deferred<{ items: typeof entitlement[]; total: number }>();
        api.list.mockReset()
            .mockResolvedValueOnce({ items: [entitlement], total: 1 })
            .mockReturnValueOnce(refresh.promise);
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        fireEvent.click(screen.getByRole("button", { name: "accessRequestsAdminEditEntitlement" }));
        fireEvent.click(screen.getByRole("button", { name: "accessRequestsAdminSave" }));
        await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
        expect(screen.getByRole("heading", { name: "Finance Reader" })).toBeVisible();

        await act(async () => refresh.resolve({ items: [{ ...entitlement, displayName: "Updated" }], total: 1 }));
        expect(screen.getByRole("heading", { name: "Updated" })).toBeVisible();
    });

    it("ignores an obsolete page error after a successful newer load", async () => {
        const oldPage = deferred<{ items: typeof entitlement[]; total: number }>();
        api.list.mockReset()
            .mockResolvedValueOnce({ items: [entitlement], total: 40 })
            .mockReturnValueOnce(oldPage.promise)
            .mockResolvedValueOnce({ items: [{ ...entitlement, displayName: "Current page" }], total: 40 });
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        fireEvent.click(screen.getAllByRole("button", { name: "Go to next page" })[0]);
        await waitFor(() => expect(api.list).toHaveBeenCalledWith({ page: 1, size: 20 }));
        fireEvent.click(screen.getAllByRole("button", { name: "Go to previous page" })[0]);
        await screen.findByRole("heading", { name: "Current page" });

        await act(async () => oldPage.reject(new Error("Stale failure")));
        expect(screen.getByRole("heading", { name: "Current page" })).toBeVisible();
        expect(screen.queryByText("accessRequestsAdminErrorUnexpected")).not.toBeInTheDocument();
    });

    it("waits for a meaningful reference search and cancels a superseded lookup", async () => {
        const user = userEvent.setup();
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminCreateEntitlement" }));
        const search = screen.getByRole("textbox", { name: "accessRequestsAdminSearchResources" });
        await user.type(search, "f");
        await new Promise((resolve) => window.setTimeout(resolve, 350));
        expect(api.references).not.toHaveBeenCalled();

        await user.type(search, "i");
        await waitFor(() => expect(api.references).toHaveBeenCalledWith("REALM_ROLE",
            expect.objectContaining({ search: "fi" })));
        const signal = api.references.mock.calls[0][1].signal as AbortSignal;
        await user.type(search, "n");
        expect(signal.aborted).toBe(true);
    });

    it("loads later reference pages and keeps the selected resource visible", async () => {
        const user = userEvent.setup();
        api.references.mockImplementation((_type, query) => Promise.resolve(query.first === 0
            ? { items: [{ id: "reader-1", name: "Reader 1", type: "REALM_ROLE", description: "" }], nextFirst: 1, hasMore: true }
            : { items: [{ id: "reader-2", name: "Reader 2", type: "REALM_ROLE", description: "" }], nextFirst: 2, hasMore: false }));
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminCreateEntitlement" }));
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminSearchResources" }), "reader");
        await screen.findByRole("option", { name: "Reader 1" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminReferencesLoadMore" }));
        await screen.findByRole("option", { name: "Reader 2" });

        expect(api.references).toHaveBeenCalledWith("REALM_ROLE", expect.objectContaining({ first: 1, search: "reader" }));
        expect(screen.getByRole("option", { name: "Reader 1" })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "accessRequestsAdminReferencesLoadMore" })).not.toBeInTheDocument();
        await user.selectOptions(screen.getByRole("combobox", { name: "accessRequestsAdminSelectResource" }), "reader-2");
        expect(screen.getByRole("option", { name: "Reader 2" })).toBeInTheDocument();
        await user.clear(screen.getByRole("textbox", { name: "accessRequestsAdminSearchResources" }));
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminSearchResources" }), "other");
        await waitFor(() => expect(screen.getByRole("combobox", { name: "accessRequestsAdminSelectResource" }))
            .toHaveValue("reader-2"));
        expect(screen.getByRole("option", { name: "Reader 2" })).toBeInTheDocument();
    });

    it("keeps loaded references and retries a failed next page", async () => {
        const user = userEvent.setup();
        let nextPageAttempts = 0;
        api.references.mockImplementation((_type, query) => {
            if (query.first === 0) {
                return Promise.resolve({
                    items: [{ id: "reader-1", name: "Reader 1", type: "REALM_ROLE", description: "" }],
                    nextFirst: 1,
                    hasMore: true
                });
            }
            nextPageAttempts++;
            return nextPageAttempts === 1
                ? Promise.reject(new Error("Unavailable"))
                : Promise.resolve({
                    items: [{ id: "reader-2", name: "Reader 2", type: "REALM_ROLE", description: "" }],
                    nextFirst: 2,
                    hasMore: false
                });
        });
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminCreateEntitlement" }));
        await user.type(screen.getByRole("textbox", { name: "accessRequestsAdminSearchResources" }), "reader");
        await screen.findByRole("option", { name: "Reader 1" });
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminReferencesLoadMore" }));
        await screen.findByText("accessRequestsAdminErrorUnexpected");
        expect(screen.getByRole("option", { name: "Reader 1" })).toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminReferencesLoadMore" }));
        await screen.findByRole("option", { name: "Reader 2" });
        expect(nextPageAttempts).toBe(2);
    });

    it("warns about composite group access only while creating a group entitlement", async () => {
        const user = userEvent.setup();
        api.list.mockResolvedValue({
            items: [{ ...entitlement, resourceType: "GROUP" }], page: 0, size: 20, total: 1
        });
        render(<EntitlementCatalogPage />);

        await screen.findByRole("heading", { name: "Finance Reader" });
        expect(screen.queryByText("accessRequestsAdminGroupAccessWarning")).not.toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminCreateEntitlement" }));
        const resourceType = screen.getByRole("combobox", { name: "accessRequestsAdminResourceType" });
        expect(screen.queryByText("accessRequestsAdminGroupAccessWarning")).not.toBeInTheDocument();
        await user.selectOptions(resourceType, "GROUP");
        expect(screen.getByText("accessRequestsAdminGroupAccessWarning")).toBeVisible();
        await user.selectOptions(resourceType, "CLIENT_ROLE");
        expect(screen.queryByText("accessRequestsAdminGroupAccessWarning")).not.toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminCancel" }));
        await user.click(screen.getByRole("button", { name: "accessRequestsAdminEditEntitlement" }));
        expect(screen.queryByText("accessRequestsAdminGroupAccessWarning")).not.toBeInTheDocument();
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
