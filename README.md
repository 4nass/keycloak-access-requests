# Keycloak Access Requests

Keycloak Access Requests is a Keycloak provider for requesting, approving, and provisioning access to realm roles, client roles, and groups.

It is delivered as one JAR containing the server provider, database migrations, and optional Account, Admin Console, and e-mail themes.

## Compatibility

| Extension version | Verified Keycloak versions | Build baseline |
| --- | --- | --- |
| `1.0.0` | 26.7.0–26.7.5, 26.8.0 | 26.8.0 |

The extension follows its own versioning; the table, rather than its version number, states the tested Keycloak runtimes. Version `1.0.0` uses Keycloak 26.8.0 for compilation, and CI tests that build baseline on every listed runtime. Keycloak 26.5.x and 26.6.x are not supported.

## Quick start

Build the provider with Java 21 and Maven 3.9 or newer:

```bash
mvn --batch-mode --no-transfer-progress package
```

Copy `target/keycloak-access-requests.jar` to the Keycloak `providers/` directory, run `kc.sh build`, then restart Keycloak. The provider API works without replacing any realm theme. The bundled console and e-mail themes are optional reference integrations; configure SMTP and an e-mail template integration before enabling lifecycle e-mails.

The complete deployment, configuration, and verification procedure is in [the installation guide](doc/installation.md).

## What it provides

- an entitlement catalog with a resource, risk level, approver role, and requestable state;
- user-facing request, history, cancellation, and approval flows;
- access-package membership provisioning, with mapped realm and client roles, after approval;
- persisted request and entitlement audit history;
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
