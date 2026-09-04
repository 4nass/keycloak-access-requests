# keycloak-access-requests

A lightweight Keycloak extension for managing access requests for realm roles, client roles, and groups.

## Scope

The project focuses on this workflow:

`Catalog → Request → Approval → Provisioning → Audit`

The scope includes:

- an explicit catalog of requestable entitlements;
- request creation, viewing, and cancellation;
- approval or rejection by an authorized approver;
- synchronous provisioning for realm roles, client roles, and groups;
- idempotency, realm isolation, and self-approval protection;
- an immutable business history;
- Keycloak REST endpoints and Account/Admin Console themes;
- PostgreSQL and Liquibase for persistence.

Advanced governance, temporary access, revocation, notifications, and external connectors are out of scope.

## Console responsibilities

The Account Console is for end users and approvers. It exposes **Request access**, **My Requests**, and **Approvals**. It never exposes entitlement administration.

The entitlement catalog belongs to the Keycloak Admin Console. It manages the configured entitlement, its requestability, risk level, and approver role. Its REST endpoints remain available for automation.

## Compatibility

The extension is a single-target provider. Each extension release is versioned to its Keycloak baseline and supports that Keycloak minor line only. A new Keycloak minor line requires a dedicated extension release and build; one provider JAR is never built for multiple Keycloak minor lines.

| Extension version | Keycloak minor line | Minimum tested version | Quarkus BOM | Validation |
|---|---:|---:|---:|---|
| 26.7.3-SNAPSHOT | 26.7.x | 26.7.3 | 3.33.3.1 | Main CI |

The current line starts at Keycloak 26.7.3. Later 26.7 patch releases must be revalidated before being declared supported. Keycloak 26.5.x and 26.6.x are not supported.

## Structure

```text
keycloak-access-requests/
├── themes/
│   └── access-requests-ui/             # Shared React workspace for all console themes
│       ├── src/
│       │   ├── account/                 # Account Console application
│       │   └── admin/                   # Admin Console application
│       ├── e2e/                         # Browser tests against a packaged Keycloak theme
│       ├── playwright.config.ts
│       ├── tsconfig.json
│       ├── tsconfig.node.json
│       ├── vite.config.ts
│       ├── package.json                 # Shared dependencies, builds, tests, and development commands
│       ├── pnpm-lock.yaml
│       └── scripts/                     # Console-specific Keycloak development launchers
├── src/
│   ├── main/
│   │   ├── java/ch/anass/keycloak/accessrequests/
│   │   │   ├── core/                    # Domain model, ports, and business services
│   │   │   ├── persistence/jpa/         # JPA entities and repository adapters
│   │   │   └── spi/                     # Keycloak integration as it is implemented
│   │   │       ├── realm/               # Realm resource provider and endpoints
│   │   │       └── jpa/                 # Keycloak JPA entity provider
│   │   └── resources/
│   │       ├── META-INF/                # Provider registrations
│   │       └── theme/access-requests/   # Packaged Account, Admin, and email theme resources
│   └── test/
│       ├── java/
│       └── resources/
└── pom.xml
```

The project is a single Maven module. The Admin and email source directories are created with their first implementation; empty placeholder directories are not tracked.

## Requirements

- Java 21+
- Maven 3.9+
- Node.js 24.18.1 and pnpm 11.18.0 are provisioned by Maven for reproducible builds
- Extension version: `26.7.3-SNAPSHOT`
- Keycloak 26.7.3 and Quarkus 3.33.3.1 as the development baseline
- Keycloak 26.7.x is the only supported Keycloak minor line
- PostgreSQL for integration tests

## Client access

The catalog API accepts access tokens with the `access-requests-api` audience. Configure this in the Keycloak Admin Console for each realm:

1. Create an OIDC client named `access-requests-api`. It represents this API and does not need login flows.
2. Create an OIDC client scope named `access-requests-api`.
3. Add an **Audience** mapper to the scope. Set **Included Client Audience** to `access-requests-api` and enable **Add to access token**.
4. Add this scope as a **Default** client scope to each client that may call the catalog API.

To revoke a client, remove the scope from that client. Newly issued tokens will no longer be accepted.

## Catalog administration

Create the realm role `manage-access-requests` and assign it only to the administrators who manage this extension. It is the only role accepted by the administrative catalog API; `manage-realm` and `manage-users` do not grant access to it.

The API is available under the realm resource:

- `GET /admin/entitlements?page=0&size=20` lists drafts and requestable entitlements;
- `POST /admin/entitlements` creates a draft (`requestable=false`);
- `GET /admin/entitlements/{id}` returns one entitlement;
- `PUT /admin/entitlements/{id}` updates its metadata and `requestable` state.

The Keycloak resource selected at creation is immutable. A `PUT` includes the current `version`; a stale version returns `409 Conflict` so an administrator cannot overwrite another administrator's change.

## Commands

Run these commands from the project root:

```bash
mvn validate
mvn test
mvn package
```

The build produces `target/keycloak-access-requests.jar`. It includes the Keycloak providers and its console themes in one deployable JAR.

## Account Console theme

The Account Console theme is named `access-requests` and extends `keycloak.v3`. Maven builds its React assets and packages them with the FreeMarker `index.ftl` bootstrap.

To enable it in a realm:

1. Copy `target/keycloak-access-requests.jar` to Keycloak's `providers` directory.
2. Run `kc.sh build` for an optimized Keycloak installation, then restart the server.
3. In **Realm settings → Themes**, select `access-requests` as the **Account theme**.

The theme packages the native Account Console shell and the **Request access**, **My Requests**, and **Approvals** pages. The UI is covered by component, route, and API-client tests.

## Account Console local development

The Account Console uses the same Vite and Keycloak workflow as the official Account Console scaffold. Run the commands in two terminals:

```bash
cd themes/access-requests-ui
pnpm install
pnpm run account:dev
```

```bash
mvn package
cd themes/access-requests-ui
pnpm run account:start-keycloak
```

`account:start-keycloak` downloads Keycloak `26.7.3` once to `themes/access-requests-ui/server/`, installs the built provider JAR, and starts it in development mode with `KC_ACCOUNT_VITE_URL=http://localhost:5173`. Open `http://localhost:8080/realms/master/account` and sign in with `admin` / `admin`.

Pass Keycloak development options after `--`, for example `pnpm run account:start-keycloak -- --http-port=8181`. To start an existing Keycloak installation instead of the managed local server, set `KEYCLOAK_HOME`; its providers directory is intentionally not changed by this script.

`pnpm run account:start-keycloak:packaged` starts the same local server without Vite. It serves the Account Console theme and its assets from the provider JAR, which is the mode exercised by the CI browser tests.

## Account Console browser tests

The UI follows Keycloak's Playwright setup and runs the same scenarios in Chromium and Firefox. With Vite and `start-keycloak` running in separate terminals, install the browsers once and run:

```bash
cd themes/access-requests-ui
pnpm exec playwright install chromium firefox
pnpm run test:e2e
```

## Admin Console theme

The Admin Console theme is also named `access-requests` and extends `keycloak.v2`. Enable Keycloak's `declarative-ui` feature, then select it as the **Admin Console theme** in **Realm settings → Themes**. It packages the standard Keycloak Admin Console shell plus an **Access requests** navigation entry and the access entitlement catalog page.

For local development, build the provider and run the Admin Vite server alongside Keycloak:

```bash
cd themes/access-requests-ui
pnpm run admin:dev
```

```bash
mvn package
cd themes/access-requests-ui
pnpm run admin:start-keycloak
```

`admin:start-keycloak` uses `KC_ADMIN_VITE_URL=http://localhost:5174`; `admin:start-keycloak:packaged` runs the same local server without Vite.

The default target is `http://localhost:8080/realms/master/account/` with the local `admin` / `admin` bootstrap account. Set `KEYCLOAK_ACCOUNT_CONSOLE_URL`, `KEYCLOAK_TEST_USERNAME`, and `KEYCLOAK_TEST_PASSWORD` to target another development environment.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
