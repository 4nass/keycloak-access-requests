import { describe, expect, it } from "vitest";

import { routes } from "./routes";

describe("Administration Console routes", () => {
    it("registers catalog and notification operations as first-class Admin Console routes without declarative-ui", () => {
        const children = routes[0].children ?? [];
        const catalogIndex = children.findIndex((route) => route.path === "/:realm/access-requests");
        const notificationDeliveryIndex = children.findIndex(
            (route) => route.path === "/:realm/access-requests/notification-deliveries"
        );
        const failedProvisioningIndex = children.findIndex(
            (route) => route.path === "/:realm/access-requests/provisioning-failures"
        );
        const revocationFailuresIndex = children.findIndex(
            (route) => route.path === "/:realm/access-requests/revocation-failures"
        );
        const auditShellIndex = children.findIndex((route) => route.children?.some(
            (child) => child.path === "/:realm/access-requests/events"
        ));
        const auditRoutes = children[auditShellIndex]?.children ?? [];
        const notFoundIndex = children.findIndex((route) => route.path === "*");

        expect(catalogIndex).toBeGreaterThanOrEqual(0);
        expect(catalogIndex).toBeLessThan(notFoundIndex);
        expect(notificationDeliveryIndex).toBeGreaterThanOrEqual(0);
        expect(notificationDeliveryIndex).toBeLessThan(notFoundIndex);
        expect(failedProvisioningIndex).toBeGreaterThanOrEqual(0);
        expect(failedProvisioningIndex).toBeLessThan(notFoundIndex);
        expect(revocationFailuresIndex).toBeGreaterThanOrEqual(0);
        expect(revocationFailuresIndex).toBeLessThan(notFoundIndex);
        expect(auditShellIndex).toBeGreaterThanOrEqual(0);
        expect(auditShellIndex).toBeLessThan(notFoundIndex);
        expect(auditRoutes.some((route) => route.path === "/:realm/access-requests/events")).toBe(true);
        expect(auditRoutes.some((route) => route.path === "/:realm/access-requests/requests/:requestId")).toBe(true);
        expect(children.some((route) => route.path === "/:realm/page-section/access-requests")).toBe(false);
    });
});
