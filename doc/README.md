# Documentation

This documentation describes the behavior that is implemented in the current provider. It is written for Keycloak administrators, API consumers, and contributors.

## For administrators

1. [Install or upgrade the provider](installation.md).
2. [Configure client access, delegated catalog managers, and localization](configuration.md).
3. [Create and operate entitlements](workflow.md).
4. [Use the Account and Admin Console themes](consoles.md).

## For API consumers

- [REST API reference](api.md) covers authentication, paths, pagination, payloads, status codes, and error responses.

## For contributors

- [Architecture](architecture.md) explains module boundaries, persistence, authorization, and the theme strategy.
- [Development and testing](development.md) describes the local toolchain and the test layers.

## Compatibility policy

The provider is intentionally built for one Keycloak minor line at a time. The current baseline is Keycloak 26.7.3 and the supported line is 26.7.x. Before declaring compatibility with a later Keycloak patch or a new minor line, rebuild the provider and run the complete verification suite against that runtime.

Keycloak 26.5.x and 26.6.x are not supported.
