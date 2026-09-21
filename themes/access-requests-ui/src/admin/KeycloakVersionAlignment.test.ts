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

async function themePackage() {
    return JSON.parse(await readFile(packagePath, "utf-8")) as ThemePackage;
}

describe("Keycloak UI dependency alignment", () => {
    it("keeps the theme package and Keycloak UI packages on the Maven compatibility version", async () => {
        const [version, theme] = await Promise.all([keycloakVersion(), themePackage()]);

        expect(theme.version.replace(/-SNAPSHOT$/, "")).toBe(version);
        keycloakUiPackages.forEach((dependency) => {
            expect(theme.dependencies[dependency], dependency).toBe(version);
        });
    });
});
