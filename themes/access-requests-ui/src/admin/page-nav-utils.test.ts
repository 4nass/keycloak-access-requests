import { describe, expect, it } from "vitest";

import { normalizeNavRoutePath } from "./page-nav-utils";

describe("Keycloak Admin navigation route normalization", () => {
    it("keeps a direct route unchanged", () => {
        expect(normalizeNavRoutePath("/users")).toBe("/users");
    });

    it("removes dynamic route parameters", () => {
        expect(normalizeNavRoutePath("/users/:id")).toBe("/users");
    });

    it("removes the wildcard suffix introduced by Keycloak 26.7.4", () => {
        expect(normalizeNavRoutePath("/users/*")).toBe("/users");
    });
});
