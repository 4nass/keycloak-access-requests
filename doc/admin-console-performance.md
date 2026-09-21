# Admin Console performance

## Scope

The Admin Console theme replaces the complete Keycloak Admin Console so that the entitlement catalog behaves like a built-in page. It imports Keycloak's public Admin UI package, its standard routes, and PatternFly styles.

The package already lazy-loads several advanced Keycloak pages. The Access requests catalog route is also lazy-loaded, so its page code and API client are requested only when an administrator opens **Access requests**.

## What is measured

The packaged-console Playwright suite opens a new browser context, clears login resource timings before authentication, and measures the cold transfer of resources served from Keycloak's `/resources/` endpoint after the Admin Console loads.

The managed local Keycloak server runs Keycloak's standard `start-dev` mode, which serves the theme assets without HTTP compression. The initial transfer budget is therefore **5.5 MB** and covers the bytes actually delivered to Chromium and Firefox. The test also fails on uncaught JavaScript errors. It runs through Maven with:

```bash
mvn verify -Pplaywright-e2e -DskipTests -DskipITs
```

This is a transfer budget, not an uncompressed Vite chunk-size budget. It deliberately covers the JavaScript, CSS, fonts, and other static console assets that the browser actually requests. Production deployments should enable HTTP compression in their reverse proxy or ingress; that is an infrastructure concern and is not forced by the provider.

## CSS audit

The theme imports `@keycloak/keycloak-admin-ui/styles.css` as well as PatternFly base and addons styles. The latter two imports match Keycloak 26.7.3's own Admin Console entry point. They remain explicit to preserve the upstream styling contract; removing them solely to reduce the generated CSS could introduce subtle visual and accessibility regressions.

The generated main CSS is therefore monitored through the browser transfer budget rather than hidden with Vite's `chunkSizeWarningLimit`. CSS imports may be reconsidered only after a visual regression run against a packaged Keycloak server in light mode, dark mode, desktop, mobile, keyboard navigation, and a screen reader.

## Architectural limit

The dominant initial JavaScript is the `@keycloak/keycloak-admin-ui` package needed to keep every standard Admin Console route available. Splitting that package with `manualChunks` would change cache boundaries but would not reduce the first load because the application shell, navigation, and standard routes need it immediately.

A material reduction requires Keycloak to provide a stable way to contribute one page and navigation entry to the stock Admin Console. Until then, the custom-console approach is intentional and its performance is governed by the browser budget above.
