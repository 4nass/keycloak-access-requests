import { expect, test, type Page } from "@playwright/test";

const adminConsoleUrl = process.env.KEYCLOAK_ADMIN_CONSOLE_URL ?? "http://localhost:8080/admin/master/console/";
const username = process.env.KEYCLOAK_TEST_USERNAME ?? "admin";
const password = process.env.KEYCLOAK_TEST_PASSWORD ?? "admin";
const initialConsoleAssetBudgetBytes = 5_500_000;

async function signIn(page: Page) {
    await page.goto(adminConsoleUrl);
    await page.locator("#username").fill(username);
    await page.locator("#password").fill(password);
    await page.evaluate(() => performance.clearResourceTimings());
    await page.locator("#kc-login").click();

    await expect(page).toHaveURL(/\/admin\/master\/console\//);
}

async function initialThemeAssetBytes(page: Page) {
    return page.evaluate(() => performance.getEntriesByType("resource")
        .filter((entry): entry is PerformanceResourceTiming => entry instanceof PerformanceResourceTiming)
        .filter((entry) => entry.name.includes("/resources/"))
        .reduce((bytes, entry) => bytes + entry.transferSize, 0));
}

test("renders Access requests as a native Administration Console entry", async ({ page }) => {
    await signIn(page);
    const configure = page.getByRole("region", { name: "Configure" });
    await expect(configure).toBeVisible();

    const accessRequests = configure.getByRole("link", { name: "Access requests" });
    await expect(accessRequests).toBeVisible();
    await accessRequests.focus();
    await expect(accessRequests).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: "Access requests" })).toBeVisible();
});

test("keeps the cold Administration Console asset transfer within budget", async ({ page }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));

    await signIn(page);
    await expect(page.getByRole("region", { name: "Configure" })).toBeVisible();

    expect(await initialThemeAssetBytes(page)).toBeLessThanOrEqual(initialConsoleAssetBudgetBytes);
    expect(pageErrors).toEqual([]);
});
