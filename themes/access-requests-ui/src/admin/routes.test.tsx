import { describe, expect, it } from "vitest";

import { routes } from "./routes";

describe("Administration Console routes", () => {
    it("registers the catalog as a first-class Admin Console route without declarative-ui", () => {
        const children = routes[0].children ?? [];
        const catalogIndex = children.findIndex((route) => route.path === "/:realm/access-requests");
        const notFoundIndex = children.findIndex((route) => route.path === "*");

        expect(catalogIndex).toBeGreaterThanOrEqual(0);
        expect(catalogIndex).toBeLessThan(notFoundIndex);
        expect(children.some((route) => route.path === "/:realm/page-section/access-requests")).toBe(false);
    });
});
