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

    await expect(page).toHaveURL(/\/admin\/[^/]+\/console\//);
}

async function initialThemeAssetBytes(page: Page) {
    return page.evaluate(() => performance.getEntriesByType("resource")
        .filter((entry): entry is PerformanceResourceTiming => entry instanceof PerformanceResourceTiming)
        .filter((entry) => entry.name.includes("/resources/"))
        .reduce((bytes, entry) => bytes + entry.transferSize, 0));
}

async function expectSelectTextClearOfCaret(page: Page, selector: string) {
    const gap = await page.locator(selector).evaluate((select: HTMLSelectElement) => {
        const caret = select.parentElement?.querySelector(".pf-v5-c-form-control__toggle-icon");
        if (!caret) return null;

        const style = getComputedStyle(select);
        const canvas = document.createElement("canvas");
        const context = canvas.getContext("2d");
        if (!context) return null;
        context.font = style.font;
        const textWidth = context.measureText(select.selectedOptions[0]?.text ?? "").width;
        const textEnd = select.getBoundingClientRect().left + parseFloat(style.paddingLeft) + textWidth;
        return caret.getBoundingClientRect().left - textEnd;
    });

    expect(gap).not.toBeNull();
    expect(gap!).toBeGreaterThanOrEqual(4);
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
    await expect(page).toHaveURL(/#\/master\/access-requests(?:\/)?$/);
    await expect(page.getByRole("heading", { name: "Catalog" })).toBeVisible();
});

test("keeps the cold Administration Console asset transfer within budget", async ({ page }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));

    await signIn(page);
    await expect(page.getByRole("region", { name: "Configure" })).toBeVisible();

    expect(await initialThemeAssetBytes(page)).toBeLessThanOrEqual(initialConsoleAssetBudgetBytes);
    expect(pageErrors).toEqual([]);
});

test("keeps Access requests tabs in the active realm", async ({ page }) => {
    await signIn(page);
    const realm = new URL(adminConsoleUrl).pathname.split("/")[2];
    const base = `/${realm}/access-requests`;
    await page.goto(`${adminConsoleUrl}#${base}`);
    await expect(page.getByRole("tab")).toHaveCount(4);

    for (const [index, suffix] of [
        [1, "/assurance-policy"],
        [2, "/incidents"],
        [3, "/events"],
        [0, ""]
    ] as const) {
        await page.getByRole("tab").nth(index).click();
        await expect(page).toHaveURL(`${adminConsoleUrl}#${base}${suffix}`);
        await expect(page.getByRole("tab")).toHaveCount(4);
        await expect(page.getByRole("tab").nth(index)).toHaveAttribute("aria-selected", "true");
    }
});

test("keeps select labels clear of their carets across Admin pages", async ({ page }) => {
    await signIn(page);
    const realm = new URL(adminConsoleUrl).pathname.split("/")[2];
    const base = `/${realm}/access-requests`;

    await page.goto(`${adminConsoleUrl}#${base}`);
    await expect(page.getByRole("combobox", { name: "Catalog view" })).toBeVisible();
    await expectSelectTextClearOfCaret(page, 'select[aria-label="Catalog view"]');

    await page.goto(`${adminConsoleUrl}#${base}/incidents`);
    await expect(page.locator("#incident-type")).toBeVisible();
    await expectSelectTextClearOfCaret(page, "#incident-type");
    await expectSelectTextClearOfCaret(page, "#incident-state");

    await page.goto(`${adminConsoleUrl}#${base}/provisioning-failures`);
    await expect(page.locator("#provisioning-failure-status")).toBeVisible();
    await expectSelectTextClearOfCaret(page, "#provisioning-failure-status");

    await page.goto(`${adminConsoleUrl}#${base}/revocation-failures`);
    await expect(page.locator("#revocation-failure-status")).toBeVisible();
    await expectSelectTextClearOfCaret(page, "#revocation-failure-status");
});
