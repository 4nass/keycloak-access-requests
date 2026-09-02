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
- Keycloak REST endpoints, declarative administration settings, and an Account Console theme;
- PostgreSQL and Liquibase for persistence.

Advanced governance, temporary access, revocation, notifications, and external connectors are out of scope.

## Console responsibilities

The Account Console is for end users and approvers. It exposes **Request access**, **My Requests**, and **Approvals**. It never exposes entitlement administration.

The entitlement catalog belongs to the Keycloak Admin Console. Its REST endpoints remain available for automation and for the future Admin Console integration.

## Compatibility

The extension is built against Keycloak 26.7.3 and verified with one integration test for each supported Keycloak minor line. Each target uses the Quarkus BOM shipped by that exact Keycloak release.

| Keycloak line | Version tested | Quarkus BOM | Testcontainers-Keycloak | Testcontainers | Validation |
|---|---:|---:|---:|---:|---|
| 26.7.x | 26.7.3 | 3.33.3.1 | 4.3.1 | 2.0.5 | Main CI |
| 26.6.x | 26.6.4 | 3.33.2.1 | 4.2.0 | 2.0.4 | Compatibility matrix |
| 26.5.x | 26.5.7 | 3.27.3 | 4.1.1 | 2.0.3 | Compatibility matrix |

The table records tested compatibility. It does not guarantee compatibility with every future patch release.

## Structure

```text
keycloak-access-requests/
├── access-requests-ui/                 # React source for the Account Console theme
│   ├── src/
│   ├── package.json
│   └── pnpm-lock.yaml
├── src/
│   ├── main/
│   │   ├── java/ch/anass/keycloak/accessrequests/
│   │   │   ├── core/                    # Domain model, ports, and business services
│   │   │   ├── persistence/jpa/         # JPA entities and repository adapters
│   │   │   └── spi/                     # Keycloak integration as it is implemented
│   │   │       ├── realm/               # Realm resource provider and endpoints
│   │   │       └── jpa/                 # Keycloak JPA entity provider
│   │   └── resources/
│   │       ├── META-INF/                # Provider registrations and theme descriptor
│   │       └── theme/access-requests/   # Account Console FreeMarker bootstrap
│   └── test/
│       ├── java/
│       └── resources/
└── pom.xml
```

The project is a single Maven module. Keycloak-specific packages and resources are created with their first implementation; empty placeholder directories are not tracked.

## Requirements

- Java 21+
- Maven 3.9+
- Node.js 24.18.1 and pnpm 11.18.0 are provisioned by Maven for reproducible builds
- Extension version: `0.1.0-SNAPSHOT`
- Keycloak 26.7.3 and Quarkus 3.33.3.1 as the development baseline
- Keycloak 26.5.x, 26.6.x, and 26.7.x are covered by compatibility tests
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

The build produces `target/keycloak-access-requests.jar`. It includes the Keycloak providers and the Account Console theme in one deployable JAR.

## Account Console theme

The Account Console theme is named `access-requests` and extends `keycloak.v3`. Maven builds its React assets and packages them with the FreeMarker `index.ftl` bootstrap.

To enable it in a realm:

1. Copy `target/keycloak-access-requests.jar` to Keycloak's `providers` directory.
2. Run `kc.sh build` for an optimized Keycloak installation, then restart the server.
3. In **Realm settings → Themes**, select `access-requests` as the **Account theme**.

The current theme packages the native Account Console shell. The Access Request pages are added in the following feature.

## Account Console local development

The Account Console uses the same Vite and Keycloak workflow as the official Account Console scaffold. Run the commands in two terminals:

```bash
cd access-requests-ui
pnpm install
pnpm run dev
```

```bash
mvn package
cd access-requests-ui
pnpm run start-keycloak
```

`start-keycloak` downloads Keycloak `26.7.3` once to `access-requests-ui/server/`, installs the built provider JAR, and starts it in development mode with `KC_ACCOUNT_VITE_URL=http://localhost:5173`. Open `http://localhost:8080/realms/master/account` and sign in with `admin` / `admin`.

Pass Keycloak development options after `--`, for example `pnpm run start-keycloak -- --http-port=8181`. To start an existing Keycloak installation instead of the managed local server, set `KEYCLOAK_HOME`; its providers directory is intentionally not changed by this script.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
