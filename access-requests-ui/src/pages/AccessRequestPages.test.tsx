import { createInstance } from "i18next";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { I18nextProvider } from "react-i18next";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { AccessRequestNavigation } from "./AccessRequestNavigation";
import { ApprovalsPage } from "./ApprovalsPage";
import { MyRequestsPage } from "./MyRequestsPage";
import { RequestAccessPage } from "./RequestAccessPage";

const testI18n = createInstance();

const messageBundle = await readFile(
    resolve(
        dirname(fileURLToPath(import.meta.url)),
        "../../../src/main/resources/theme/access-requests/account/messages/messages_en.properties"
    ),
    "utf8"
);
const messages = Object.fromEntries(
    messageBundle
        .split(/\r?\n/)
        .filter((line) => line && !line.startsWith("#"))
        .map((line) => {
            const separator = line.indexOf("=");
            return [line.slice(0, separator), line.slice(separator + 1)];
        })
);

await testI18n.init({
    initImmediate: false,
    interpolation: {
        escapeValue: false
    },
    lng: "en",
    resources: {
        en: {
            translation: messages
        }
    }
});

function renderAccessRequestUi(ui: ReactNode) {
    return render(
        <MemoryRouter>
            <I18nextProvider i18n={testI18n}>{ui}</I18nextProvider>
        </MemoryRouter>
    );
}

describe("Access Request account console pages", () => {
    it("ships every access request message used by the feature pages", () => {
        expect(Object.keys(messages).sort()).toEqual([
            "accessRequestsAlreadyGranted",
            "accessRequestsApprove",
            "accessRequestsApproveEntitlement",
            "accessRequestsApproved",
            "accessRequestsApprovals",
            "accessRequestsCancel",
            "accessRequestsCancelRequest",
            "accessRequestsCanceled",
            "accessRequestsClose",
            "accessRequestsConfirmApproval",
            "accessRequestsConfirmRejection",
            "accessRequestsDecision",
            "accessRequestsDecisionComment",
            "accessRequestsGranted",
            "accessRequestsHistory",
            "accessRequestsJustification",
            "accessRequestsLoading",
            "accessRequestsLoadError",
            "accessRequestsMyRequests",
            "accessRequestsNav",
            "accessRequestsPending",
            "accessRequestsProvisioning",
            "accessRequestsReject",
            "accessRequestsRejectEntitlement",
            "accessRequestsRejected",
            "accessRequestsRequestAccess",
            "accessRequestsRequestAccessTo",
            "accessRequestsRequestDetails",
            "accessRequestsRequestPending",
            "accessRequestsRequestedBy",
            "accessRequestsResourceType",
            "accessRequestsRetry",
            "accessRequestsRisk",
            "accessRequestsRiskLabel",
            "accessRequestsSubmitRequest",
            "accessRequestsViewDetails"
        ].sort());
    });

    it("falls back to the English theme messages when a locale is not supplied", async () => {
        const fallbackI18n = createInstance();
        await fallbackI18n.init({
            fallbackLng: "en",
            lng: "fr",
            resources: {
                en: {
                    translation: messages
                }
            }
        });

        expect(fallbackI18n.t("accessRequestsRequestAccess")).toBe("Request access");
    });

    it("shows Request access and My Requests to every requester, but hides Approvals without an approval scope", () => {
        renderAccessRequestUi(<AccessRequestNavigation canApprove={false} />);

        const navigation = screen.getByRole("navigation", { name: "Access" });
        expect(within(navigation).getByRole("link", { name: "Request access" })).toBeVisible();
        expect(within(navigation).getByRole("link", { name: "My Requests" })).toBeVisible();
        expect(within(navigation).queryByRole("link", { name: "Approvals" })).not.toBeInTheDocument();
    });

    it("shows Approvals only to a user with at least one entitlement approval scope", () => {
        renderAccessRequestUi(<AccessRequestNavigation canApprove />);

        expect(screen.getByRole("link", { name: "Approvals" })).toBeVisible();
    });

    it("lists published requestable access and submits a justification", async () => {
        const user = userEvent.setup();
        const requestAccess = vi.fn().mockResolvedValue(undefined);

        renderAccessRequestUi(
            <RequestAccessPage
                entries={[
                    {
                        id: "finance-reader",
                        name: "Finance Reader",
                        description: "Read-only access to Finance Portal",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "LOW",
                        alreadyGranted: false,
                        pendingRequest: false
                    },
                    {
                        id: "finance-administrator",
                        name: "Finance Administrator",
                        description: "Administer the Finance Portal",
                        resourceType: "REALM_ROLE",
                        riskLevel: "HIGH",
                        alreadyGranted: true,
                        pendingRequest: false
                    },
                    {
                        id: "vpn-production",
                        name: "VPN Production",
                        description: "Connect to the production network",
                        resourceType: "GROUP",
                        riskLevel: "HIGH",
                        alreadyGranted: false,
                        pendingRequest: true
                    }
                ]}
                onRequest={requestAccess}
            />
        );

        const financeReader = screen.getByRole("article", { name: "Finance Reader" });
        expect(within(financeReader).getByText("Read-only access to Finance Portal")).toBeVisible();
        expect(within(financeReader).getByText("CLIENT_ROLE")).toBeVisible();
        expect(within(financeReader).getByText("Risk: LOW")).toBeVisible();

        await user.click(within(financeReader).getByRole("button", { name: "Request access" }));
        const dialog = screen.getByRole("dialog", { name: "Request access to Finance Reader" });
        const justification = "I need to review month-end finance reports.";
        await user.type(within(dialog).getByLabelText("Justification"), justification);
        await user.click(within(dialog).getByRole("button", { name: "Submit request" }));

        expect(requestAccess).toHaveBeenCalledWith({
            entitlementId: "finance-reader",
            justification
        });
        expect(within(screen.getByRole("article", { name: "Finance Administrator" }))
            .getByText("Already granted")).toBeVisible();
        expect(within(screen.getByRole("article", { name: "VPN Production" }))
            .getByText("Request pending")).toBeVisible();
    });

    it("keeps the request dialog open, prevents duplicate submission, and refreshes only after a successful request", async () => {
        const user = userEvent.setup();
        let resolveSubmission: (() => void) | undefined;
        const refreshRequests = vi.fn();
        const requestAccess = vi.fn(
            () =>
                new Promise<void>((resolve) => {
                    resolveSubmission = resolve;
                })
        );

        renderAccessRequestUi(
            <RequestAccessPage
                entries={[
                    {
                        id: "finance-reader",
                        name: "Finance Reader",
                        description: "Read-only access to Finance Portal",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "LOW",
                        alreadyGranted: false,
                        pendingRequest: false
                    }
                ]}
                onRequest={requestAccess}
                onRefresh={refreshRequests}
            />
        );

        const requestButton = screen.getByRole("button", { name: "Request access" });
        await user.click(requestButton);
        const dialog = screen.getByRole("dialog", { name: "Request access to Finance Reader" });
        const submitButton = within(dialog).getByRole("button", { name: "Submit request" });
        await user.type(within(dialog).getByLabelText("Justification"), "I need finance reports.");

        await user.click(submitButton);
        await user.click(submitButton);

        expect(requestAccess).toHaveBeenCalledTimes(1);
        expect(submitButton).toBeDisabled();
        expect(dialog).toBeVisible();

        resolveSubmission?.();

        await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
        expect(refreshRequests).toHaveBeenCalledTimes(1);
    });

    it("keeps the request dialog and its justification when request submission fails", async () => {
        const user = userEvent.setup();
        const requestAccess = vi.fn().mockRejectedValue(new Error("Request unavailable"));

        renderAccessRequestUi(
            <RequestAccessPage
                entries={[
                    {
                        id: "finance-reader",
                        name: "Finance Reader",
                        description: "Read-only access to Finance Portal",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "LOW",
                        alreadyGranted: false,
                        pendingRequest: false
                    }
                ]}
                onRequest={requestAccess}
            />
        );

        await user.click(screen.getByRole("button", { name: "Request access" }));
        const dialog = screen.getByRole("dialog", { name: "Request access to Finance Reader" });
        const justification = "I need finance reports.";
        await user.type(within(dialog).getByLabelText("Justification"), justification);
        await user.click(within(dialog).getByRole("button", { name: "Submit request" }));

        await waitFor(() => expect(requestAccess).toHaveBeenCalledTimes(1));
        expect(screen.getByRole("dialog", { name: "Request access to Finance Reader" })).toBeVisible();
        expect(within(dialog).getByLabelText("Justification")).toHaveValue(justification);
        expect(screen.getByRole("alert")).toHaveTextContent("Request unavailable");
    });

    it("closes a successful request dialog but keeps a refresh failure visible on the page", async () => {
        const user = userEvent.setup();
        const requestAccess = vi.fn().mockResolvedValue(undefined);
        const refreshRequests = vi.fn().mockRejectedValue(new Error("Catalog refresh unavailable"));

        renderAccessRequestUi(
            <RequestAccessPage
                entries={[
                    {
                        id: "finance-reader",
                        name: "Finance Reader",
                        description: "Read-only access to Finance Portal",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "LOW",
                        alreadyGranted: false,
                        pendingRequest: false
                    }
                ]}
                onRequest={requestAccess}
                onRefresh={refreshRequests}
            />
        );

        await user.click(screen.getByRole("button", { name: "Request access" }));
        const dialog = screen.getByRole("dialog", { name: "Request access to Finance Reader" });
        await user.type(within(dialog).getByLabelText("Justification"), "I need finance reports.");
        await user.click(within(dialog).getByRole("button", { name: "Submit request" }));

        await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
        expect(requestAccess).toHaveBeenCalledOnce();
        expect(refreshRequests).toHaveBeenCalledOnce();
        expect(screen.getByRole("alert")).toHaveTextContent("Catalog refresh unavailable");
    });

    it("moves focus into a request dialog, closes it with Escape, and restores focus to its trigger", async () => {
        const user = userEvent.setup();

        renderAccessRequestUi(
            <RequestAccessPage
                entries={[
                    {
                        id: "finance-reader",
                        name: "Finance Reader",
                        description: "Read-only access to Finance Portal",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "LOW",
                        alreadyGranted: false,
                        pendingRequest: false
                    }
                ]}
                onRequest={vi.fn()}
            />
        );

        const requestButton = screen.getByRole("button", { name: "Request access" });
        await user.click(requestButton);
        const dialog = screen.getByRole("dialog", { name: "Request access to Finance Reader" });

        expect(within(dialog).getByLabelText("Justification")).toHaveFocus();
        await user.tab();
        expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
        await user.tab();
        expect(within(dialog).getByLabelText("Justification")).toHaveFocus();
        await user.keyboard("{Escape}");

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(requestButton).toHaveFocus();
    });

    it("shows request details and immutable history, and only permits cancellation while pending", async () => {
        const user = userEvent.setup();
        const cancelRequest = vi.fn().mockResolvedValue(undefined);

        renderAccessRequestUi(
            <MyRequestsPage
                requests={[
                    {
                        id: "request-pending",
                        entitlementName: "Finance Reader",
                        resourceType: "CLIENT_ROLE",
                        decisionStatus: "PENDING",
                        provisioningStatus: "NOT_STARTED",
                        requestedAt: "26 Aug 2026",
                        justification: "I need to review month-end finance reports.",
                        history: [{ type: "REQUEST_CREATED", occurredAt: "26 Aug 2026" }]
                    },
                    {
                        id: "request-approved",
                        entitlementName: "VPN Production",
                        resourceType: "GROUP",
                        decisionStatus: "APPROVED",
                        provisioningStatus: "SUCCEEDED",
                        requestedAt: "21 Aug 2026",
                        justification: "I support the production release.",
                        decision: {
                            decidedAt: "21 Aug 2026",
                            approver: "Finance Approver",
                            comment: "Approved for the release window."
                        },
                        history: [
                            { type: "REQUEST_CREATED", occurredAt: "20 Aug 2026" },
                            { type: "REQUEST_APPROVED", occurredAt: "21 Aug 2026" },
                            { type: "PROVISIONING_SUCCEEDED", occurredAt: "21 Aug 2026" }
                        ]
                    }
                ]}
                onCancel={cancelRequest}
            />
        );

        const pendingRequest = screen.getByRole("article", { name: "Finance Reader" });
        expect(within(pendingRequest).getByText("Pending")).toBeVisible();
        await user.click(within(pendingRequest).getByRole("button", { name: "Cancel request" }));
        expect(cancelRequest).toHaveBeenCalledWith("request-pending");

        const approvedRequest = screen.getByRole("article", { name: "VPN Production" });
        expect(within(approvedRequest).getByText("Granted 21 Aug 2026")).toBeVisible();
        await user.click(within(approvedRequest).getByRole("button", { name: "View details" }));

        const details = screen.getByRole("dialog", { name: "VPN Production request details" });
        expect(within(details).getByText("I support the production release.")).toBeVisible();
        expect(within(details).getByText("Finance Approver")).toBeVisible();
        expect(within(details).getByText("Approved for the release window.")).toBeVisible();
        expect(within(details).getByText("SUCCEEDED")).toBeVisible();
        expect(within(details).getByText("REQUEST_CREATED")).toBeVisible();
        expect(within(details).getByText("REQUEST_APPROVED")).toBeVisible();
        expect(within(details).getByText("PROVISIONING_SUCCEEDED")).toBeVisible();
        expect(within(approvedRequest).queryByRole("button", { name: "Cancel request" })).not.toBeInTheDocument();
    });

    it("loads request details on demand instead of showing summary placeholders", async () => {
        const user = userEvent.setup();
        let resolveDetails: ((details: {
            justification: string;
            decision: { approver: string; comment: string; decidedAt: string };
            history: Array<{ type: string; occurredAt: string }>;
        }) => void) | undefined;
        const loadDetails = vi.fn(
            () => new Promise<{
                justification: string;
                decision: { approver: string; comment: string; decidedAt: string };
                history: Array<{ type: string; occurredAt: string }>;
            }>((resolve) => {
                resolveDetails = resolve;
            })
        );

        renderAccessRequestUi(
            <MyRequestsPage
                requests={[
                    {
                        id: "request-1",
                        entitlementName: "Finance Reader",
                        resourceType: "CLIENT_ROLE",
                        decisionStatus: "APPROVED",
                        provisioningStatus: "SUCCEEDED",
                        requestedAt: "26 Aug 2026",
                        justification: "",
                        history: []
                    }
                ]}
                onCancel={vi.fn()}
                onRequestDetails={loadDetails}
            />
        );

        await user.click(screen.getByRole("button", { name: "View details" }));

        expect(loadDetails).toHaveBeenCalledWith("request-1");
        const dialog = screen.getByRole("dialog", { name: "Finance Reader request details" });
        expect(within(dialog).getByText("Loading access requests")).toBeVisible();

        resolveDetails?.({
            justification: "I need month-end reports.",
            decision: {
                approver: "finance-approver",
                comment: "Approved.",
                decidedAt: "26 Aug 2026"
            },
            history: [{ type: "REQUEST_APPROVED", occurredAt: "26 Aug 2026" }]
        });

        await waitFor(() => expect(within(dialog).getByText("I need month-end reports.")).toBeVisible());
        expect(within(dialog).getByText("finance-approver")).toBeVisible();
        expect(within(dialog).getByText("REQUEST_APPROVED")).toBeVisible();
    });

    it("prevents duplicate cancellation and refreshes requests only after cancellation succeeds", async () => {
        const user = userEvent.setup();
        let resolveCancellation: (() => void) | undefined;
        const cancelRequest = vi.fn(
            () =>
                new Promise<void>((resolve) => {
                    resolveCancellation = resolve;
                })
        );
        const refreshRequests = vi.fn();

        renderAccessRequestUi(
            <MyRequestsPage
                requests={[
                    {
                        id: "request-pending",
                        entitlementName: "Finance Reader",
                        resourceType: "CLIENT_ROLE",
                        decisionStatus: "PENDING",
                        provisioningStatus: "NOT_STARTED",
                        requestedAt: "26 Aug 2026",
                        justification: "I need to review month-end finance reports.",
                        history: [{ type: "REQUEST_CREATED", occurredAt: "26 Aug 2026" }]
                    }
                ]}
                onCancel={cancelRequest}
                onRefresh={refreshRequests}
            />
        );

        const cancelButton = screen.getByRole("button", { name: "Cancel request" });
        await user.click(cancelButton);
        await user.click(cancelButton);

        expect(cancelRequest).toHaveBeenCalledTimes(1);
        expect(cancelButton).toBeDisabled();

        resolveCancellation?.();

        await waitFor(() => expect(refreshRequests).toHaveBeenCalledTimes(1));
    });

    it("keeps a failed cancellation visible and lets the requester retry", async () => {
        const user = userEvent.setup();
        const cancelRequest = vi.fn().mockRejectedValue(new Error("Cancellation unavailable"));

        renderAccessRequestUi(
            <MyRequestsPage
                requests={[
                    {
                        id: "request-pending",
                        entitlementName: "Finance Reader",
                        resourceType: "CLIENT_ROLE",
                        decisionStatus: "PENDING",
                        provisioningStatus: "NOT_STARTED",
                        requestedAt: "26 Aug 2026",
                        justification: "I need to review month-end finance reports.",
                        history: [{ type: "REQUEST_CREATED", occurredAt: "26 Aug 2026" }]
                    }
                ]}
                onCancel={cancelRequest}
            />
        );

        const cancelButton = screen.getByRole("button", { name: "Cancel request" });
        await user.click(cancelButton);

        await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Cancellation unavailable"));
        expect(cancelButton).toBeEnabled();
    });

    it("shows pending approvals and sends explicit approve or reject decisions", async () => {
        const user = userEvent.setup();
        const approve = vi.fn().mockResolvedValue(undefined);
        const reject = vi.fn().mockResolvedValue(undefined);

        renderAccessRequestUi(
            <ApprovalsPage
                requests={[
                    {
                        id: "request-approval",
                        requester: "Anass Chahbouni",
                        entitlementName: "Finance Reader",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "HIGH",
                        justification: "I need to reconcile finance data before closing.",
                        requestedAt: "26 Aug 2026"
                    }
                ]}
                onApprove={approve}
                onReject={reject}
            />
        );

        const pendingRequest = screen.getByRole("article", { name: "Finance Reader requested by Anass Chahbouni" });
        expect(within(pendingRequest).getByText("Risk: HIGH")).toBeVisible();
        expect(within(pendingRequest).getByText("I need to reconcile finance data before closing.")).toBeVisible();

        await user.click(within(pendingRequest).getByRole("button", { name: "Approve" }));
        const approvalDialog = screen.getByRole("dialog", { name: "Approve Finance Reader" });
        await user.type(within(approvalDialog).getByLabelText("Decision comment"), "Approved for month-end close.");
        await user.click(within(approvalDialog).getByRole("button", { name: "Confirm approval" }));
        expect(approve).toHaveBeenCalledWith({
            requestId: "request-approval",
            comment: "Approved for month-end close."
        });

        await user.click(within(pendingRequest).getByRole("button", { name: "Reject" }));
        const rejectionDialog = screen.getByRole("dialog", { name: "Reject Finance Reader" });
        await user.type(within(rejectionDialog).getByLabelText("Decision comment"), "Please request read-only access instead.");
        await user.click(within(rejectionDialog).getByRole("button", { name: "Confirm rejection" }));
        expect(reject).toHaveBeenCalledWith({
            requestId: "request-approval",
            comment: "Please request read-only access instead."
        });
    });

    it("waits for a decision, prevents duplicate approval, and retains the dialog if it fails", async () => {
        const user = userEvent.setup();
        let rejectApproval: ((reason?: Error) => void) | undefined;
        const approve = vi.fn(
            () =>
                new Promise<void>((_resolve, reject) => {
                    rejectApproval = reject;
                })
        );

        renderAccessRequestUi(
            <ApprovalsPage
                requests={[
                    {
                        id: "request-approval",
                        requester: "Anass Chahbouni",
                        entitlementName: "Finance Reader",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "HIGH",
                        justification: "I need to reconcile finance data before closing.",
                        requestedAt: "26 Aug 2026"
                    }
                ]}
                onApprove={approve}
                onReject={vi.fn()}
            />
        );

        await user.click(screen.getByRole("button", { name: "Approve" }));
        const dialog = screen.getByRole("dialog", { name: "Approve Finance Reader" });
        const confirmButton = within(dialog).getByRole("button", { name: "Confirm approval" });
        await user.type(within(dialog).getByLabelText("Decision comment"), "Approved.");
        await user.click(confirmButton);
        await user.click(confirmButton);

        expect(approve).toHaveBeenCalledTimes(1);
        expect(confirmButton).toBeDisabled();
        expect(dialog).toBeVisible();

        rejectApproval?.(new Error("Decision unavailable"));

        await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Decision unavailable"));
        expect(screen.getByRole("dialog", { name: "Approve Finance Reader" })).toBeVisible();
    });

    it("closes a successful decision dialog but keeps a refresh failure visible on the page", async () => {
        const user = userEvent.setup();
        const approve = vi.fn().mockResolvedValue(undefined);
        const refreshApprovals = vi.fn().mockRejectedValue(new Error("Approval queue refresh unavailable"));

        renderAccessRequestUi(
            <ApprovalsPage
                requests={[
                    {
                        id: "request-approval",
                        requester: "Anass Chahbouni",
                        entitlementName: "Finance Reader",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "HIGH",
                        justification: "I need to reconcile finance data before closing.",
                        requestedAt: "26 Aug 2026"
                    }
                ]}
                onApprove={approve}
                onRefresh={refreshApprovals}
                onReject={vi.fn()}
            />
        );

        await user.click(screen.getByRole("button", { name: "Approve" }));
        const dialog = screen.getByRole("dialog", { name: "Approve Finance Reader" });
        await user.click(within(dialog).getByRole("button", { name: "Confirm approval" }));

        await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
        expect(approve).toHaveBeenCalledOnce();
        expect(refreshApprovals).toHaveBeenCalledOnce();
        expect(screen.getByRole("alert")).toHaveTextContent("Approval queue refresh unavailable");
    });

    it("moves focus into an approval dialog, closes it with Escape, and restores focus to its trigger", async () => {
        const user = userEvent.setup();

        renderAccessRequestUi(
            <ApprovalsPage
                requests={[
                    {
                        id: "request-approval",
                        requester: "Anass Chahbouni",
                        entitlementName: "Finance Reader",
                        resourceType: "CLIENT_ROLE",
                        riskLevel: "HIGH",
                        justification: "I need to reconcile finance data before closing.",
                        requestedAt: "26 Aug 2026"
                    }
                ]}
                onApprove={vi.fn()}
                onReject={vi.fn()}
            />
        );

        const approveButton = screen.getByRole("button", { name: "Approve" });
        await user.click(approveButton);
        const dialog = screen.getByRole("dialog", { name: "Approve Finance Reader" });

        expect(within(dialog).getByLabelText("Decision comment")).toHaveFocus();
        await user.tab();
        expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
        await user.tab();
        expect(within(dialog).getByRole("button", { name: "Confirm approval" })).toHaveFocus();
        await user.tab();
        expect(within(dialog).getByLabelText("Decision comment")).toHaveFocus();
        await user.keyboard("{Escape}");

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(approveButton).toHaveFocus();
    });
});
