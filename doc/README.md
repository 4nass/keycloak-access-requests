# Documentation

This documentation describes the behavior that is implemented in the current provider. It is written for Keycloak administrators, API consumers, and contributors.

## For administrators

1. [Install or upgrade the provider](installation.md).
2. [Configure client access, delegated operations, and localization](configuration.md).
3. [Create and operate entitlements](workflow.md).
4. [Use the Account and Admin Console themes](consoles.md).
5. [Browse screenshots of the deployed consoles and request workflow](screenshots/README.md).

## For API consumers

- [REST API reference](api.md) covers authentication, paths, pagination, payloads, status codes, and error responses.

## For contributors

- [Architecture](architecture.md) explains module boundaries, persistence, authorization, and the theme strategy.
- [Development and testing](development.md) describes the local toolchain and the test layers.

## Compatibility policy

The published `1.0.0` JAR was built against Keycloak 26.8.0. Version `1.0.1` uses Keycloak 26.8.1 and passed both CI matrices for 26.7.0–26.7.5 and 26.8.0–26.8.1. Extension versions are independent of Keycloak versions; the [compatibility table](../README.md#compatibility) records the verified runtime set for each artifact. The build baseline is pinned, not floated automatically. Before declaring support for an artifact, run the complete suite against every claimed runtime without changing its build baseline.

Keycloak 26.5.x and 26.6.x are not supported.
