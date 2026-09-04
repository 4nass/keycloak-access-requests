import path from "node:path";

export function providerJarPath(workspaceDirectory) {
    return path.join(
        path.resolve(workspaceDirectory, "..", ".."),
        "target",
        "keycloak-access-requests.jar"
    );
}
