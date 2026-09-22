# Architecture

## Deployment model

The project is one Maven module and produces one deployable JAR. The JAR contains:

- Keycloak realm-resource and JPA provider registrations;
- core domain and application services;
- JPA persistence adapters and Liquibase changelog;
- Account and Admin Console theme descriptors, translations, and compiled assets.

This model lets Keycloak own transactions, persistence lifecycle, provider discovery, and theme delivery. There is no separate application server or database managed by this project.

## Source layout

```text
src/main/java/ch/anass/keycloak/accessrequests/
├── core/
│   ├── domain/        # State, value objects, queries, and invariants
│   ├── port/          # Interfaces required by the business services
│   └── service/       # Request, catalog, queue, policy, and authorization use cases
├── persistence/jpa/   # Entities, repositories, history writers, and transactions
└── spi/
    ├── jpa/           # Keycloak JPA entity provider and Liquibase registration
    ├── notification/  # Transactional email outbox and Keycloak timer delivery worker
    ├── provisioning/  # Keycloak role and group provisioning adapter
    └── realm/         # Realm REST resource and Keycloak security adapters

themes/access-requests-ui/
├── src/account/       # Account Console React application
├── src/admin/         # Admin Console React application
├── e2e/               # Playwright tests against a packaged provider
└── scripts/           # Local Keycloak launchers
```

## Request path

```text
Account Console or API client
        │
        ▼
Realm resource: /realms/{realm}/access-requests
        │
        ├── authenticate bearer token and audience
        ├── enforce Keycloak admin permissions where required
        ▼
Core services and domain invariants
        │
        ├── JPA repositories and optimistic locking
        ├── immutable request and entitlement history
        ├── transactional notification outbox
        └── Keycloak provisioner
        ▼
Keycloak database, roles, and groups
```

## Domain boundaries

`core/domain` defines the vocabulary and state transitions. It does not depend on Keycloak or JPA.

`core/port` defines the infrastructure capabilities required by the services: repositories, transaction handling, history publication, role membership, user status, existing-access checks, and provisioning.

`core/service` coordinates use cases. It protects request state, request ownership, duplicate pending requests, approval authorization, self-approval, concurrency, and provisioning outcomes.

`persistence/jpa` and `spi` implement those ports with Keycloak's runtime APIs. This keeps Keycloak-specific types outside the domain and makes the core unit-testable without a running server.

## Persistence and consistency

Every entity is realm-scoped. Repository lookups include the realm ID, and cross-realm access resolves as not found or unauthorized.

The database enforces one pending request per requester and entitlement. Updates to requests and entitlements use optimistic locking through their version. A simultaneous duplicate or stale update becomes a controlled conflict instead of silently overwriting data.

The JPA provider owns the Liquibase changelog. Its tables are deliberately prefixed `AR_` to keep the provider's schema objects recognizable in the shared Keycloak database.

### Notification delivery

Lifecycle e-mails are written as recipient-specific rows in `AR_NOTIFICATION_OUTBOX` in the same transaction as the request, decision, and audit event. The HTTP request therefore never calls SMTP, and a rolled-back business transaction leaves no delivery to send.

A Keycloak timer leases due rows and sends them in the background. The lease and processor token prevent two cluster nodes from handling the same row concurrently. The unique delivery key (`event`, notification type, recipient) makes enqueueing idempotent; transient delivery failures are retried with backoff, then retained as `FAILED` after ten attempts for operational follow-up. A missing, disabled, or e-mail-less recipient is recorded as `DISCARDED`.

SMTP itself cannot offer atomic exactly-once delivery with the database: a process failure after SMTP accepts a message but before the outbox acknowledgement can cause one retry. The provider consequently offers durable, at-least-once delivery with idempotent queueing rather than claiming exactly-once e-mail delivery.

## Security model

The realm resource has two different entry points:

- requester and approver actions require a Keycloak access token with the `access-requests-api` audience;
- catalog administration requires Keycloak administrative authorization and, for a delegated manager, the `manage-access-requests` realm role.

The server checks ownership for cancellation, an effective entitlement-specific approver role for decisions, and self-approval prevention. The UI only mirrors these permissions for usability.

## Console strategy

The Account application is a focused integration in the public Account Console surface. The Admin application is a complete custom Admin Console theme built on Keycloak's public Admin UI package so it can introduce a catalog route without relying on Keycloak's experimental server features.

### Admin navigation maintenance

Keycloak exposes the Admin UI's pages, routes, providers, and `PageNav` through its public UI package. It does not currently expose a stable contribution API for adding one item to the stock navigation. `PageNav` itself does not accept contributed entries.

The theme therefore maintains a small fork of Keycloak's `PageNav` in `AccessRequestsAdminPageNav.tsx`. Its baseline is Keycloak 26.7.3 and the only product-specific behavior is the **Access requests** catalog item. The item is visible only after a capability check; the realm API remains authoritative for authorization.

Using Keycloak's `declarative-ui` server feature only for this link would make the provider depend on an experimental feature being enabled at runtime. The local fork avoids that runtime requirement at the cost of an explicit upgrade review.

For every supported Keycloak minor release:

1. Update the Keycloak runtime and direct `@keycloak/*` UI dependencies together.
2. Diff `AccessRequestsAdminPageNav.tsx` against the upstream `PageNav` for that exact release.
3. Carry over navigation groups, authorization behavior, feature flags, accessibility fixes, and visual changes that affect the fork.
4. Run the unit, packaged JAR, browser, light/dark, keyboard, and authorization checks.

`KeycloakVersionAlignment.test.ts` verifies that Maven's Keycloak baseline, the theme package version, and direct `@keycloak/*` dependencies stay aligned. If Keycloak later provides a stable navigation contribution API, only this component and route registration need replacement; the catalog page, API client, server authorization, and persistence remain unchanged.
