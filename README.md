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
- Keycloak REST endpoints, declarative administration settings, and optional theme fragments;
- PostgreSQL and Liquibase for persistence.

Advanced governance, temporary access, revocation, notifications, and external connectors are out of scope.

## Structure

```text
keycloak-access-requests/
├── src/
│   ├── main/
│   │   ├── java/ch/anass/keycloak/accessrequests/
│   │   │   ├── core/                    # Domain model, ports, and business services
│   │   │   ├── persistence/jpa/         # JPA entities and repository adapters
│   │   │   └── spi/                     # Keycloak integration as it is implemented
│   │   │       ├── realm/               # Realm resource provider and endpoints
│   │   │       ├── jpa/                 # Keycloak JPA entity provider
│   │   │       └── ui/                  # Declarative Admin UI providers
│   │   └── resources/
│   │       ├── META-INF/services/       # Keycloak provider registrations
│   │       └── theme/                   # Optional FreeMarker theme fragments
│   └── test/
│       ├── java/
│       └── resources/
└── pom.xml
```

The project is a single Maven module. Keycloak-specific packages and resources are created with their first implementation; empty placeholder directories are not tracked.

## Requirements

- Java 21+
- Maven 3.9+
- Keycloak 26.4.x as the development baseline
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

The build produces `target/keycloak-access-requests.jar`. When the Keycloak SPI implementations are added, this single JAR will be copied to Keycloak's `providers` directory.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
