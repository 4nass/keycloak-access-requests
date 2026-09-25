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

The provider is intentionally built for one Keycloak minor line at a time. The current build baseline is Keycloak 26.7.4; complete runtime validation covers 26.7.0 through 26.7.4. The extension's own first release on this line will be `26.7.0`, with subsequent patch numbers reserved for extension revisions. The build baseline is pinned to the latest verified patch, not floated automatically. Before declaring compatibility with another Keycloak patch or a new minor line, update the baseline and run the complete verification suite against every claimed runtime.

Keycloak 26.5.x and 26.6.x are not supported.
