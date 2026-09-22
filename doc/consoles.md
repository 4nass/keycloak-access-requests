# Console themes

The provider packages two optional Keycloak console themes under the shared theme name `access-requests`.

## Choose a console integration

The server provider and realm API work without selecting either bundled console theme. This is the recommended default for a realm that already owns an Account or Admin theme, or that exposes access requests through another portal.

Keycloak allows one Account theme and one Admin Console theme per realm; it does not offer a stable mechanism for automatically composing a route into any third-party theme. The bundled themes are therefore complete reference integrations, not mandatory dependencies. Selecting one replaces the matching realm theme.

Teams that keep their own theme can integrate the realm API and reproduce the relevant pages in their maintained theme. They should treat the bundled React applications as implementation references and keep server-side API authorization as the security boundary.

## Account Console

The Account Console is the end-user experience. It adds an **Access requests** navigation group with:

- **Request access**: browse the requestable catalog, search it, and submit a justification;
- **My Requests**: view request status, decision details, and immutable history; cancel pending requests;
- **Approvals**: view and decide requests only when the signed-in user can approve at least one entitlement.

The theme extends `keycloak.v3`, uses Keycloak Account Console components and PatternFly patterns, and obtains data from the realm API. Dates, request states, history, risk levels, and feedback messages are localized.

The navigation is not an authorization mechanism. The API remains responsible for audience validation, ownership checks, approver-role checks, and self-approval prevention.

## Admin Console

The Admin Console owns entitlement configuration. **Configure → Access requests** provides:

- a paged catalog including drafts and published entitlements;
- search-backed Keycloak resource and approver-role selectors;
- creation of draft entitlements;
- metadata, risk, approver-role, and requestable-state updates;
- optimistic-lock feedback when another administrator changed the same entitlement.

The theme extends `keycloak.v2` and integrates a React application built from Keycloak's public Admin UI package. It follows the native Keycloak layout, navigation behavior, localization, PatternFly components, light/dark mode, and keyboard patterns.

The Admin UI is visible only after the capability check succeeds. The server still enforces Keycloak administrator access and `manage-access-requests` for non-realm administrators.

## Enable a theme

For a realm that chooses the bundled reference UI:

1. Open **Realm settings → Themes**.
2. Choose `access-requests` as the **Account theme**, **Admin Console theme**, or both.
3. Save the realm and start a new browser session if the previous theme is cached.

See [realm configuration](configuration.md) for authorization and localization setup.

## Local development

The React workspace is `themes/access-requests-ui`. Open two terminals after building the provider:

```bash
mvn package
```

For the Account Console:

```bash
cd themes/access-requests-ui
pnpm install
pnpm run account:dev
pnpm run account:start-keycloak
```

For the Admin Console:

```bash
cd themes/access-requests-ui
pnpm install
pnpm run admin:dev
pnpm run admin:start-keycloak
```

The development launcher downloads Keycloak 26.7.3 once under `themes/access-requests-ui/server/`, installs the packaged provider, and starts a local server. It uses `KC_ACCOUNT_VITE_URL=http://localhost:5173` for Account development and `KC_ADMIN_VITE_URL=http://localhost:5174` for Admin development.

Use `account:start-keycloak:packaged` or `admin:start-keycloak:packaged` to exercise the assets from the JAR without a Vite server.

## Browser verification

The packaged browser suite runs both console applications in Chromium and Firefox against a real Keycloak server and provider JAR. It checks navigation, routes, theme assets, light/dark behavior, authorization scenarios, keyboard interaction, and uncaught JavaScript errors.

Install browser binaries once and run the suite against a running packaged local server:

```bash
cd themes/access-requests-ui
pnpm exec playwright install chromium firefox
pnpm run test:e2e
```

The CI workflow runs the same suite after packaging the provider. See [development and testing](development.md) for the Maven lifecycle.

## Admin Console performance

The Admin theme replaces the full Keycloak Admin Console so that the entitlement catalog can behave like a built-in page. It must load Keycloak's public Admin UI package, its standard routes, and PatternFly styles. This is structurally larger than the focused Account Console integration.

The entitlement catalog is lazy-loaded: its page code, API client, and route CSS are requested only after an administrator opens **Access requests**. The application shell and standard Keycloak routes still load initially because the theme supplies the complete Admin Console.

The packaged Playwright suite measures resources served from Keycloak's `/resources/` endpoint after a cold sign-in and fails on uncaught JavaScript errors. The budget is **5.5 MB** for Keycloak `start-dev`, which serves these assets without HTTP compression. It covers the bytes actually transferred for JavaScript, CSS, fonts, and static assets in Chromium and Firefox.

This is a browser transfer budget, not a Vite raw-chunk-size target. Production deployments should enable HTTP compression at the reverse proxy or ingress; it is infrastructure configuration and is not forced by the provider.

The theme deliberately keeps `@keycloak/keycloak-admin-ui/styles.css` as well as PatternFly base and addons styles. The PatternFly imports match Keycloak 26.7.3's own Admin Console entry point. Removing them only to reduce CSS size could cause subtle visual or accessibility regressions, so any such change requires packaged visual checks in light/dark mode, desktop/mobile layouts, keyboard navigation, and a screen reader.

A `manualChunks` configuration would only change cache boundaries: it would not materially reduce the first load while the full Admin Console shell, navigation, and standard routes remain necessary. A major reduction would require Keycloak to provide a stable API for contributing one page and navigation item to the stock Admin Console.
