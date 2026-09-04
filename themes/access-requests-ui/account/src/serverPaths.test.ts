import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

import { providerJarPath } from "../server-paths.js";

it("resolves the provider JAR from the extension project root", () => {
    const sourceDirectory = dirname(fileURLToPath(import.meta.url));
    const accountThemeDirectory = resolve(sourceDirectory, "..");
    const extensionProjectDirectory = resolve(sourceDirectory, "..", "..", "..", "..");

    expect(providerJarPath(accountThemeDirectory)).toBe(
        resolve(extensionProjectDirectory, "target", "keycloak-access-requests.jar")
    );
});
