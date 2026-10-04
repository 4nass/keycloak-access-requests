# Console screenshot gallery

These images are captured in a browser against Keycloak with the built extension JAR deployed. Names, requests, notification deliveries, and operational incidents are disposable test fixtures, not production data. The populated operational states are served through the extension's real HTTP endpoints; the browser test seeds exceptional failure states in its disposable PostgreSQL database.

| Page | Empty state | Populated state |
| --- | --- | --- |
| Account · Catalog | [Empty](account-catalog-empty-light.png) | [Light](account-catalog-light.png), [requestable package](account-catalog-filled-light.png), [dark](account-catalog-dark.png) |
| Account · My requests | [Light](account-my-requests-light.png), [dark](account-my-requests-dark.png) | [Pending](account-my-requests-filled-light.png), [approved details](workflow-my-requests-approved.png) |
| Account · Approvals | [Light](account-approvals-light.png), [dark](account-approvals-dark.png) | [Pending decision](account-approvals-filled-light.png) |
| Admin · Catalog | [Empty](admin-catalog-empty-light.png) | [Light](admin-catalog-light.png), [dark](admin-catalog-dark.png) |
| Admin · Provisioning failures | [Open, empty](admin-failed-provisioning-light.png), [closed, empty](admin-provisioning-failures-closed-empty-light.png) | [Open failure](admin-provisioning-failures-filled-light.png), [closed failure](admin-provisioning-failures-closed-light.png) |
| Admin · Email notifications | [Light](admin-email-notifications-light.png), [dark](admin-email-notifications-dark.png) | [Summary and failed deliveries](admin-email-notifications-filled-light.png) |
| Admin · Revocation failures | [Open, empty](admin-revocation-failures-light.png), [open, dark](admin-revocation-failures-dark.png), [resolved, empty](admin-revocation-failures-resolved-empty-light.png) | [Open incident](admin-revocation-failures-filled-light.png), [resolved incident](admin-revocation-failures-resolved-filled-light.png) |
| Admin · Events | [Empty search](admin-events-light.png) | [Event history](admin-events-filled-light.png), [request details](workflow-admin-request-history.png) |

The Admin setup captures show the [access package form](workflow-admin-create-access-package.png), its [selected role](workflow-admin-create-access-package-roles.png), the [package review](workflow-admin-review-access-package.png), and the [requestable setting before publication](workflow-admin-publish-access-package.png).

The [submission](workflow-request-access.png), [pending request](workflow-my-requests-pending.png), and [approver queue](workflow-approvals-pending.png) captures show the rest of the request journey. Light and dark refer to the browser color scheme used by the tests.

Creating an access package also creates its associated catalog entitlement and dedicated delivery group. The Admin Console has one creation path; administrators review the package policy before opening it to requests. Direct entitlement drafts remain available to API clients but cannot be published as requestable access.

Each populated screenshot shows all data displayed by that page in its pictured state; it is not a claim that every lifecycle transition or every possible failure code can appear in one image. The actual tests also assert loaded API responses and the absence of browser JavaScript errors.

To regenerate the gallery, run `AccessRequestAccountConsoleBrowserIT` and `AccessRequestAdminConsoleBrowserIT` with `-Daccess.requests.screenshot.dir=doc/screenshots` during `mvn verify`. Docker must be available for Keycloak, PostgreSQL, and Chrome Testcontainers.
