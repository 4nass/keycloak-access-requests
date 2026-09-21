# Admin Console navigation

## Why the navigation is maintained locally

The Access requests entitlement catalog must be discoverable from the Keycloak Administration Console. The extension is delivered as a React Admin Console theme built with `@keycloak/keycloak-admin-ui`.

Keycloak exposes `PageNav`, routes, providers, and page components through its public UI package. It does not provide a stable, documented contribution API for adding one navigation item to the standard Admin Console navigation. The `PageNav` export itself does not accept contributed entries.

Using `declarative-ui` only to add the navigation item would require an experimental server feature and create a runtime dependency on that feature being enabled.

## Implementation

We keep a small, controlled fork of Keycloak's `PageNav` in `AccessRequestsAdminPageNav.tsx`. Its upstream baseline is Keycloak `26.7.3`; the only product-specific behavior is the **Access requests** catalog item.

The catalog item is shown only after the extension capability endpoint authorizes it. The server endpoint remains authoritative: hiding a navigation item never grants or removes access.

The Maven `keycloak.version`, theme package version, and direct `@keycloak/*` UI dependencies must remain aligned. `KeycloakVersionAlignment.test.ts` enforces this in the UI test suite.

## Upgrade policy

For each supported Keycloak minor release:

1. Update the Keycloak runtime and all direct `@keycloak/*` UI dependencies together.
2. Diff `AccessRequestsAdminPageNav.tsx` against Keycloak's `PageNav` for that release.
3. Carry over relevant navigation groups, permissions, feature flags, accessibility changes, and visual changes.
4. Run the unit, packaged JAR, browser, light/dark, and authorization test suites.
5. Release a matching extension version only after this review.

Patch upgrades remain covered by the same automated suite. Any upstream UI change that affects the custom theme is handled before publishing the compatible extension release.

## Trade-off

The navigation remains visually and behaviorally consistent with Keycloak, without an experimental feature flag. The cost is an explicit, localized review during Keycloak upgrades. If Keycloak later introduces a stable navigation contribution API, only this navigation component and route registration need to be replaced; the catalog pages, client, API, and server authorization remain unchanged.
