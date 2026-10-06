import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ capabilities: vi.fn() }));
vi.mock("../api/useEntitlementsAdminApi", () => ({ useEntitlementsAdminApi: () => api }));

import { AuthorizedAuditPage } from "./AuthorizedAuditPage";
import { EntitlementCatalogRoute } from "./EntitlementCatalogRoute";
import { FailedProvisioningRoute } from "./FailedProvisioningRoute";
import { NotificationDeliveryRoute } from "./NotificationDeliveryRoute";
import { RevocationFailuresRoute } from "./RevocationFailuresRoute";

describe("Admin route error frames", () => {
    beforeEach(() => {
        api.capabilities.mockReset().mockRejectedValue(new TypeError("network unavailable"));
    });

    it.each([
        ["/master/access-requests", "accessRequestsAdminCatalogTab", <EntitlementCatalogRoute />],
        ["/master/access-requests/notification-deliveries", "accessRequestsAdminNotificationDelivery", <NotificationDeliveryRoute />],
        ["/master/access-requests/provisioning-failures", "accessRequestsAdminFailedProvisioning", <FailedProvisioningRoute />],
        ["/master/access-requests/revocation-failures", "accessRequestsAdminRevocationFailures", <RevocationFailuresRoute />],
        ["/master/access-requests/events", "accessRequestsAdminEvents", <AuthorizedAuditPage><p>Protected audit</p></AuthorizedAuditPage>]
    ])("keeps the %s heading and active navigation during a capability error", async (path, heading, route) => {
        render(<MemoryRouter initialEntries={[path]}>{route}</MemoryRouter>);

        expect(await screen.findByText("accessRequestsAdminErrorUnavailable")).toBeVisible();
        expect(screen.getByRole("heading", { level: 1, name: heading })).toBeVisible();
        expect(screen.getByRole("tab", { name: heading })).toBeDisabled();
        expect(screen.queryByText("Protected audit")).not.toBeInTheDocument();
    });
});
