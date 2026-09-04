import { describe, expect, it } from "vitest";

import { routes } from "./routes";

describe("Administration Console routes", () => {
    it("registers the navigation route before Keycloak's generic page-section route", () => {
        const children = routes[0].children ?? [];
        const navigationIndex = children.findIndex((route) => route.path === "/:realm/page-section/access-requests");
        const genericPageIndex = children.findIndex((route) => route.path === "/:realm?/page-section/:providerId");
        const catalogIndex = children.findIndex((route) => route.path === "/:realm/access-requests");
        const notFoundIndex = children.findIndex((route) => route.path === "*");

        expect(navigationIndex).toBeGreaterThanOrEqual(0);
        expect(navigationIndex).toBeLessThan(genericPageIndex);
        expect(catalogIndex).toBeGreaterThanOrEqual(0);
        expect(catalogIndex).toBeLessThan(notFoundIndex);
    });
});
