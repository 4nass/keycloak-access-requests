# Keycloak Access Requests

Keycloak Access Requests is a Keycloak provider for requesting, approving, and provisioning access to realm roles, client roles, and groups.

It is delivered as one JAR containing the server provider, database migrations, Account Console theme, and Admin Console theme.

## Supported baseline

| Extension version | Supported Keycloak line | Tested baseline |
| --- | --- | --- |
| `26.7.3-SNAPSHOT` | 26.7.x | 26.7.3 |

One extension release targets one Keycloak minor line. Keycloak 26.5.x and 26.6.x are not supported.

## Quick start

Build the provider with Java 21 and Maven 3.9 or newer:

```bash
mvn --batch-mode --no-transfer-progress package
```

Copy `target/keycloak-access-requests.jar` to the Keycloak `providers/` directory, run `kc.sh build`, then restart Keycloak. Select the `access-requests` Account and Admin Console themes in **Realm settings → Themes**.

The complete deployment, configuration, and verification procedure is in [the installation guide](doc/installation.md).

## What it provides

- an entitlement catalog with a resource, risk level, approver role, and requestable state;
- user-facing request, history, cancellation, and approval flows;
- synchronous provisioning of realm roles, client roles, and groups after approval;
- immutable request and entitlement audit history;
- realm-scoped REST endpoints and native-looking Keycloak console themes.

## Documentation

Start with the [documentation index](doc/README.md):

- [Installation and upgrades](doc/installation.md)
- [Realm configuration and authorization](doc/configuration.md)
- [Workflow and entitlement model](doc/workflow.md)
- [REST API reference](doc/api.md)
- [Console themes](doc/consoles.md)
- [Architecture](doc/architecture.md)
- [Development and testing](doc/development.md)

## License

This project is licensed under the [Apache License 2.0](LICENSE).
