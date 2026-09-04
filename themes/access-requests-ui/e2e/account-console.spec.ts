import { expect, test } from "@playwright/test";

const accountConsoleUrl = process.env.KEYCLOAK_ACCOUNT_CONSOLE_URL ?? "http://localhost:8080/realms/master/account/";
const username = process.env.KEYCLOAK_TEST_USERNAME ?? "admin";
const password = process.env.KEYCLOAK_TEST_PASSWORD ?? "admin";

test("renders the Access requests navigation in the local Account Console", async ({ page }) => {
    await page.goto(accountConsoleUrl);
    await page.locator("#username").fill(username);
    await page.locator("#password").fill(password);
    await page.locator("#kc-login").click();

    await expect(page).toHaveURL(/\/realms\/[^/]+\/account\//);
    const accessRequestsNavigation = page.getByRole("button", { name: "Access requests" });
    await expect(accessRequestsNavigation).toBeVisible();

    await accessRequestsNavigation.focus();
    await expect(accessRequestsNavigation).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(accessRequestsNavigation).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByRole("link", { name: "Request access" })).toBeVisible();
    await expect(page.getByRole("link", { name: "My Requests" })).toBeVisible();
});
