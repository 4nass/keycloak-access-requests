import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const sourceDirectory = dirname(fileURLToPath(import.meta.url));
const pomPath = resolve(sourceDirectory, "../../../../pom.xml");
const packagePath = resolve(sourceDirectory, "../../package.json");
const keycloakUiPackages = [
    "@keycloak/keycloak-account-ui",
    "@keycloak/keycloak-admin-ui",
    "@keycloak/keycloak-ui-shared"
] as const;

type ThemePackage = {
    dependencies: Record<string, string>;
    version: string;
};

async function keycloakVersion() {
    const pom = await readFile(pomPath, "utf-8");
    const match = pom.match(/<keycloak\.version>([^<]+)<\/keycloak\.version>/);

    if (!match) {
        throw new Error("The Maven keycloak.version property is missing.");
    }

    return match[1];
}

async function extensionVersion() {
    const pom = await readFile(pomPath, "utf-8");
    const match = pom.match(/<version>([^<]+)<\/version>/);

    if (!match) {
        throw new Error("The Maven project version is missing.");
    }

    return match[1];
}

async function themePackage() {
    return JSON.parse(await readFile(packagePath, "utf-8")) as ThemePackage;
}

describe("Keycloak UI dependency alignment", () => {
    it("keeps the theme release version separate from its Keycloak UI baseline", async () => {
        const [version, releaseVersion, theme] = await Promise.all([keycloakVersion(), extensionVersion(), themePackage()]);

        expect(theme.version).toBe(releaseVersion);
        keycloakUiPackages.forEach((dependency) => {
            expect(theme.dependencies[dependency], dependency).toBe(version);
        });
    });
});
