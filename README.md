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
- a REST API and a custom Account Console;
- PostgreSQL and Liquibase for persistence.

Advanced governance, temporary access, revocation, notifications, and external connectors are out of scope.

## Structure

```text
keycloak-access-requests/
├── access-requests-core/               # Domain model and business contracts
├── access-requests-persistence-jpa/    # JPA persistence and Liquibase
├── access-requests-rest/               # REST API and HTTP boundary
├── access-requests-keycloak/           # Keycloak provider integration
├── access-requests-account-ui/         # React Account Console extension
├── access-requests-integration-tests/  # Keycloak and PostgreSQL tests
└── distribution/                       # Provider distribution assembly
```

The project is a multi-module Maven reactor. The modules are empty placeholders in this first commit; no business implementation is included yet.

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
```

Supported Keycloak versions and deployment instructions will be added with the implementation.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
