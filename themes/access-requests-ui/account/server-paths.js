import path from "node:path";

export function providerJarPath(themeDirectory) {
    return path.join(
        path.resolve(themeDirectory, "..", "..", ".."),
        "target",
        "keycloak-access-requests.jar"
    );
}
