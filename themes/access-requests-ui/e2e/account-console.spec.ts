import { expect, test, type Page } from "@playwright/test";

const accountConsoleUrl = process.env.KEYCLOAK_ACCOUNT_CONSOLE_URL ?? "http://localhost:8080/realms/master/account/";
const username = process.env.KEYCLOAK_TEST_USERNAME ?? "admin";
const password = process.env.KEYCLOAK_TEST_PASSWORD ?? "admin";

async function signIn(page: Page) {
    await page.goto(accountConsoleUrl);
    await page.locator("#username").fill(username);
    await page.locator("#password").fill(password);
    await page.locator("#kc-login").click();

    await expect(page).toHaveURL(/\/realms\/[^/]+\/account\//);
}

test("renders the Access requests navigation in the local Account Console", async ({ page }) => {
    await signIn(page);
    const accessRequestsNavigation = page.getByRole("button", { name: "Access requests" });
    await expect(accessRequestsNavigation).toBeVisible();

    await accessRequestsNavigation.focus();
    await expect(accessRequestsNavigation).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(accessRequestsNavigation).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByRole("link", { name: "Catalog" })).toBeVisible();
    await expect(page.getByRole("link", { name: "My requests" })).toBeVisible();
});

test("loads enabled native Account Console pages without authorization errors", async ({ page }) => {
    await signIn(page);
    const failures: string[] = [];
    page.on("pageerror", (error) => failures.push(error.message));
    page.on("response", (response) => {
        if (response.status() >= 400 && response.url().includes("/account/")) {
            failures.push(`${response.status()} ${response.url()}`);
        }
    });

    for (const route of [
        "personal-info",
        "account-security/device-activity",
        "account-security/linked-accounts",
        "account-security/signing-in",
        "applications",
        "groups",
        "resources"
    ]) {
        if (await page.locator(`a[href$="/${route}"]`).count() === 0) {
            continue;
        }
        await page.goto(new URL(route, accountConsoleUrl).href);
        await page.waitForLoadState("networkidle");
        expect(failures, `Native Account Console route ${route}`).toEqual([]);
    }
});
