# Development and testing

## Toolchain

| Tool | Version or purpose |
| --- | --- |
| Java | 21 |
| Maven | 3.9 or newer |
| Keycloak build baseline | 26.7.4 |
| Validated Keycloak runtimes | 26.7.0–26.7.4 |
| Quarkus BOM | 3.33.3.2 |
| Node.js | 24.18.1, provisioned by Maven |
| pnpm | 11.18.0, provisioned by Maven |

Maven installs the pinned Node.js and pnpm versions in `target/frontend`. A global Node.js installation is convenient for direct UI commands, but is not required for the Maven build.

## Main commands

Run commands from the repository root unless stated otherwise.

| Command | What it verifies |
| --- | --- |
| `mvn validate` | Maven project structure and dependency resolution |
| `mvn test` | Java unit tests and Account/Admin UI unit tests |
| `mvn package` | Java compilation, theme builds, theme verification, and one provider JAR |
| `mvn verify` | Unit tests plus Java integration tests |
| `mvn verify -Pplaywright-e2e -DskipITs` | Unit and browser tests against an already running packaged Keycloak server |

`mvn clean verify` is the recommended pre-push check. Java integration tests need Docker because they use Testcontainers.
To test the same JAR against a different Keycloak runtime without changing its compilation baseline, first build it with `mvn package -DskipTests`, then run `mvn failsafe:integration-test failsafe:verify -Dkeycloak.test.version=26.7.0`. A plain `mvn verify -Dkeycloak.test.version=...` also runs the Java and UI unit tests and rebuilds the provider with the pinned Keycloak build baseline.

## UI workspace

The Account and Admin applications share one pnpm workspace:

```bash
cd themes/access-requests-ui
pnpm install
pnpm run test
pnpm run build
```

Useful focused commands:

```bash
pnpm run test:account
pnpm run test:admin
pnpm run build:account
pnpm run build:admin
pnpm run account:dev
pnpm run admin:dev
```

Use the packaged local Keycloak launchers and Playwright as described in [Console themes](consoles.md#local-development). Do not treat a Vite-only test as a deployment test: the packaged suite is the check that the JAR includes every generated asset.

## Test layers

| Layer | Location | Purpose |
| --- | --- | --- |
| Core unit tests | `src/test/java/.../core` | Domain invariants, state machine, authorization, policy, and services |
| JPA tests | `src/test/java/.../persistence` | Repository semantics, transactions, locking, and history |
| Keycloak integration tests | `src/test/java/.../spi` | Provider registration, REST behavior, JPA migrations, realm isolation, and provisioning |
| Selenium browser integration tests | `src/test/java/.../ui` | Packaged provider behavior in a Keycloak container |
| Vitest | `themes/access-requests-ui/src/**` | Components, routes, API clients, localization, and keyboard behavior |
| Playwright | `themes/access-requests-ui/e2e` | Packaged Account/Admin themes in Chromium and Firefox |

The UI tests cover success paths and failures such as `401`, `403`, `409`, network errors, empty data, concurrency feedback, and modal keyboard behavior. The browser suites additionally collect JavaScript errors and exercise the actual theme assets served by Keycloak.

## Continuous integration

Two GitHub Actions workflows protect `main` and pull requests:

- **Build and tests** runs `mvn clean verify` against Keycloak 26.7.0–26.7.4 in separate jobs, always compiling against 26.7.4 and uploading the provider JAR from each job.
- **Console E2E** packages the 26.7.4-based JAR, starts each Keycloak runtime, selects both packaged themes, and runs Playwright in Chromium and Firefox.

The workflows run when relevant source, theme, Maven, or workflow files change. CodeQL and Dependabot run independently.

## Before opening a change

1. Keep Keycloak API changes and theme behavior aligned.
2. Add or update focused tests at the lowest meaningful layer.
3. Run formatting-sensitive checks such as `git diff --check`.
4. Run `mvn clean verify` when Docker is available.
5. For console changes, run the packaged Playwright suite in both browsers.
6. Update the relevant document in `doc/` when the public API, configuration, compatibility, UI behavior, or operational procedure changes.

## Keycloak upgrades

One extension release targets one Keycloak minor line. For an upgrade:

1. Update the Maven Keycloak baseline and direct `@keycloak/*` UI packages together.
2. Align the Quarkus BOM with the Keycloak line.
3. Rebuild and run every test layer against the exact target Keycloak version.
4. Review the Account and Admin themes in light/dark mode and with keyboard navigation.
5. Diff the maintained Admin navigation component against Keycloak's upstream `PageNav`; see [Admin navigation maintenance](architecture.md#admin-navigation-maintenance).
6. Publish a release whose major and minor numbers identify the supported Keycloak line; use the patch number for the extension's own revisions.

Never state compatibility with a new Keycloak patch or minor release merely because the Java compilation succeeds.

## Publish a GitHub Release

Update the Maven project version to the release version, run the full verification
workflow, and create a matching version tag such as `v26.7.0`. The Release
workflow checks that the tag matches the Maven version, runs `mvn clean verify`,
and publishes both `keycloak-access-requests.jar` and
`keycloak-access-requests-email-theme.zip` as GitHub Release assets.
