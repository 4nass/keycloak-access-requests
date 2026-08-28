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
