import { createInstance } from "i18next";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { I18nextProvider } from "react-i18next";
import { describe, expect, it, vi } from "vitest";

import { AccessRequestNavigation } from "./AccessRequestNavigation";
import { ApprovalsPage } from "./ApprovalsPage";
import { MyRequestsPage } from "./MyRequestsPage";
import { RequestAccessPage } from "./RequestAccessPage";

const testI18n = createInstance();

await testI18n.init({
    initImmediate: false,
    interpolation: {
        escapeValue: false
    },
    lng: "en",
    resources: {
        en: {
            translation: {
                accessRequestsAlreadyGranted: "Already granted",
                accessRequestsApprove: "Approve",
                accessRequestsApproveEntitlement: "Approve {{entitlement}}",
                accessRequestsApproved: "Approved",
                accessRequestsApprovals: "Approvals",
                accessRequestsCancel: "Cancel",
                accessRequestsCancelRequest: "Cancel request",
                accessRequestsCanceled: "Canceled",
                accessRequestsClose: "Close",
                accessRequestsConfirmApproval: "Confirm approval",
                accessRequestsConfirmRejection: "Confirm rejection",
                accessRequestsDecision: "Decision",
                accessRequestsDecisionComment: "Decision comment",
                accessRequestsGranted: "Granted {{date}}",
                accessRequestsHistory: "History",
                accessRequestsJustification: "Justification",
                accessRequestsMyRequests: "My Requests",
                accessRequestsNav: "Access",
                accessRequestsPending: "Pending",
                accessRequestsProvisioning: "Provisioning",
                accessRequestsReject: "Reject",
                accessRequestsRejectEntitlement: "Reject {{entitlement}}",
                accessRequestsRejected: "Rejected",
                accessRequestsRequestAccess: "Request access",
                accessRequestsRequestAccessTo: "Request access to {{entitlement}}",
                accessRequestsRequestDetails: "{{entitlement}} request details",
                accessRequestsRequestPending: "Request pending",
                accessRequestsRequestedBy: "{{entitlement}} requested by {{requester}}",
                accessRequestsResourceType: "Resource type",
                accessRequestsRisk: "Risk: {{riskLevel}}",
                accessRequestsRiskLabel: "Risk",
                accessRequestsSubmitRequest: "Submit request",
                accessRequestsViewDetails: "View details"
            }
        }
    }
});

function renderAccessRequestUi(ui: ReactNode) {
    return render(<I18nextProvider i18n={testI18n}>{ui}</I18nextProvider>);
}

describe("Access Request account console pages", () => {
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
});
