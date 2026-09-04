import { type ViewportSize, defineConfig, devices } from "@playwright/test";

const viewport: ViewportSize = { width: 1920, height: 1080 };

/**
 * Mirrors Keycloak console browser test configuration.
 * See https://playwright.dev/docs/test-configuration.
 */
export default defineConfig({
    testDir: "./e2e",
    fullyParallel: true,
    forbidOnly: Boolean(process.env.CI),
    reporter: process.env.CI ? [["github"], ["html"]] : "list",

    use: {
        trace: "retain-on-failure"
    },

    projects: [
        {
            name: "chromium",
            use: {
                ...devices["Desktop Chrome"],
                viewport
            }
        },
        {
            name: "firefox",
            use: {
                ...devices["Desktop Firefox"],
                viewport
            }
        }
    ]
});
