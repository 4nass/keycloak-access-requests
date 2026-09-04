import { expect, test } from "@playwright/test";

const adminConsoleUrl = process.env.KEYCLOAK_ADMIN_CONSOLE_URL ?? "http://localhost:8080/admin/master/console/";
const username = process.env.KEYCLOAK_TEST_USERNAME ?? "admin";
const password = process.env.KEYCLOAK_TEST_PASSWORD ?? "admin";

test("renders Access requests as a native Administration Console entry", async ({ page }) => {
    await page.goto(adminConsoleUrl);
    await page.locator("#username").fill(username);
    await page.locator("#password").fill(password);
    await page.locator("#kc-login").click();

    await expect(page).toHaveURL(/\/admin\/master\/console\//);
    const configure = page.getByRole("button", { name: "Configure" });
    await expect(configure).toBeVisible();
    await configure.focus();
    await expect(configure).toBeFocused();
    await page.keyboard.press("Enter");

    const accessRequests = page.getByRole("link", { name: "Access requests" });
    await expect(accessRequests).toBeVisible();
    await accessRequests.click();
    await expect(page.getByRole("heading", { name: "Access requests" })).toBeVisible();
});
