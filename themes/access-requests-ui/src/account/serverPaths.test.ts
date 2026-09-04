import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

import { providerJarPath } from "../../scripts/provider-jar-path.js";

it("resolves the provider JAR from the extension project root", () => {
    const sourceDirectory = dirname(fileURLToPath(import.meta.url));
    const workspaceDirectory = resolve(sourceDirectory, "..", "..");
    const extensionProjectDirectory = resolve(workspaceDirectory, "..", "..");

    expect(providerJarPath(workspaceDirectory)).toBe(
        resolve(extensionProjectDirectory, "target", "keycloak-access-requests.jar")
    );
});
