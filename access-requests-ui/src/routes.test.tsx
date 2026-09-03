import { createMemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

vi.mock("./environment", () => ({
    environment: {
        baseUrl: "https://keycloak.example/realms/finance/account"
    }
}));

const { routes } = await import("./routes");

describe("Access Request Account Console routes", () => {
    it.each(["request-access", "my-requests", "approvals"])("resolves the %s route in the real account router", (path) => {
        const router = createMemoryRouter(routes, {
            initialEntries: [`/realms/finance/account/${path}`]
        });

        expect(router.state.matches.at(-1)?.route.path).toBe(path);
    });

    it.each(["request-access", "my-requests", "approvals"])("loads the %s page lazily", (path) => {
        const route = routes[0].children?.find((child) => child.path === path);

        expect(route?.element).toHaveProperty("type.$$typeof", Symbol.for("react.lazy"));
    });
});
