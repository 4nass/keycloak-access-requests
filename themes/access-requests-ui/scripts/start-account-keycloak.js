import { spawn } from "node:child_process";
import { createGunzip } from "node:zlib";
import fs from "node:fs";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { fileURLToPath } from "node:url";
import { extract } from "tar-fs";

import packageJson from "../package.json" with { type: "json" };
import { providerJarPath } from "./provider-jar-path.js";

const DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const WORKSPACE_DIRECTORY = path.resolve(DIRECTORY, "..");
const MANAGED_SERVER_DIRECTORY = path.join(WORKSPACE_DIRECTORY, "server");
const PROVIDER_JAR = providerJarPath(WORKSPACE_DIRECTORY);
const SCRIPT_EXTENSION = process.platform === "win32" ? ".bat" : ".sh";
const KEYCLOAK_VERSION = process.env.KEYCLOAK_VERSION ?? packageJson.dependencies["@keycloak/keycloak-account-ui"];
const accountDevMode = process.argv.includes("--account-dev");
const adminDevMode = process.argv.includes("--admin-dev");
const argumentsForKeycloak = process.argv.slice(2).filter((argument) => argument !== "--account-dev" && argument !== "--admin-dev");
const serverDirectory = process.env.KEYCLOAK_HOME
    ? path.resolve(process.env.KEYCLOAK_HOME)
    : MANAGED_SERVER_DIRECTORY;

await ensureServer(serverDirectory);

if (!process.env.KEYCLOAK_HOME) {
    await installProvider(serverDirectory);
}

startServer(serverDirectory);

async function ensureServer(directory) {
    const executable = keycloakExecutable(directory);
    if (fs.existsSync(executable)) {
        console.info(`Using Keycloak from ${directory}.`);
        return;
    }

    if (process.env.KEYCLOAK_HOME) {
        throw new Error(`KEYCLOAK_HOME does not contain ${path.join("bin", `kc${SCRIPT_EXTENSION}`)}.`);
    }

    if (fs.existsSync(directory)) {
        throw new Error(`${directory} exists but is not a valid Keycloak installation. Remove it before retrying.`);
    }

    const temporaryDirectory = `${directory}.download-${process.pid}`;
    const downloadUrl = `https://github.com/keycloak/keycloak/releases/download/${KEYCLOAK_VERSION}/keycloak-${KEYCLOAK_VERSION}.tar.gz`;

    console.info(`Downloading Keycloak ${KEYCLOAK_VERSION}…`);
    await fs.promises.mkdir(temporaryDirectory, { recursive: true });

    try {
        const response = await fetch(downloadUrl);
        if (!response.ok || !response.body) {
            throw new Error(`Unable to download Keycloak ${KEYCLOAK_VERSION}: ${response.status} ${response.statusText}`);
        }

        await pipeline(Readable.fromWeb(response.body), createGunzip(), extract(temporaryDirectory, { strip: 1 }));
        await moveServerDirectory(temporaryDirectory, directory);
    } catch (error) {
        await fs.promises.rm(temporaryDirectory, { force: true, recursive: true });
        throw error;
    }
}

async function moveServerDirectory(temporaryDirectory, directory) {
    try {
        await fs.promises.rename(temporaryDirectory, directory);
    } catch (error) {
        if (process.platform !== "win32" || error?.code !== "EPERM") {
            throw error;
        }

        await fs.promises.cp(temporaryDirectory, directory, {
            errorOnExist: true,
            force: false,
            recursive: true
        });
        await fs.promises.rm(temporaryDirectory, { force: true, recursive: true });
    }
}

async function installProvider(directory) {
    if (!fs.existsSync(PROVIDER_JAR)) {
        console.warn(`Provider JAR not found at ${PROVIDER_JAR}. Run mvn package, then restart Keycloak to test extension endpoints.`);
        return;
    }

    const providerDirectory = path.join(directory, "providers");
    await fs.promises.mkdir(providerDirectory, { recursive: true });
    await fs.promises.copyFile(PROVIDER_JAR, path.join(providerDirectory, path.basename(PROVIDER_JAR)));
}

function startServer(directory) {
    const environment = {
        ...process.env,
        KC_BOOTSTRAP_ADMIN_PASSWORD: process.env.KC_BOOTSTRAP_ADMIN_PASSWORD ?? "admin",
        KC_BOOTSTRAP_ADMIN_USERNAME: process.env.KC_BOOTSTRAP_ADMIN_USERNAME ?? "admin"
    };

    if (accountDevMode) {
        environment.KC_ACCOUNT_VITE_URL = process.env.KC_ACCOUNT_VITE_URL ?? "http://localhost:5173";
        console.info(`Starting Keycloak with Account Console HMR at ${environment.KC_ACCOUNT_VITE_URL}.`);
    } else if (adminDevMode) {
        environment.KC_ADMIN_VITE_URL = process.env.KC_ADMIN_VITE_URL ?? "http://localhost:5174";
        console.info(`Starting Keycloak with Administration Console HMR at ${environment.KC_ADMIN_VITE_URL}.`);
    } else {
        console.info("Starting Keycloak with the packaged console themes.");
    }
    const processHandle = spawn(
        keycloakExecutable(directory),
        [
            "start-dev",
            "--features=login:v2,account:v3,admin-fine-grained-authz,declarative-ui,transient-users,oid4vc-vci,organization",
            ...argumentsForKeycloak
        ],
        {
            env: environment,
            shell: process.platform === "win32",
            stdio: "inherit"
        }
    );

    processHandle.on("error", (error) => {
        console.error("Unable to start Keycloak.", error);
        process.exitCode = 1;
    });
}

function keycloakExecutable(directory) {
    return path.join(directory, "bin", `kc${SCRIPT_EXTENSION}`);
}
