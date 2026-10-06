# Realm configuration and authorization

Configure every realm independently. Entitlements, requests, approval permissions, and API authorization never cross realm boundaries.

## Optionally enable the bundled console themes

The server provider and realm API do not require a custom Account or Admin Console theme. A realm can keep its existing Keycloak themes and consume the API from another portal or a customer-maintained console integration.

The bundled `access-requests` Account and Admin themes are complete reference integrations. To use one in a realm:

1. Open **Realm settings → Themes**.
2. Select `access-requests` as the **Account theme**, **Admin Console theme**, or both.
3. Save the realm.

Selecting either theme replaces that realm's corresponding theme; it does not compose automatically with an existing customer theme. The Account theme exposes request and approval pages. The Admin theme exposes catalog administration, request events, notification delivery, and failed-provisioning operations; it does not replace the server-side authorization checks.

The **Access requests → Events** sub-tab searches the extension's existing request history, with
date, type, actor, and request filters and a link to each request's details. To also record catalog
changes in Keycloak's native Admin Events, enable **Save events** under **Realm settings → Events →
Admin events** for each realm. The extension emits `ACCESS_REQUEST_ENTITLEMENT` create/update
events only when this setting is enabled. It does not copy request lifecycle events into the
native event store.

## Configure lifecycle e-mail delivery

E-mail delivery is configured independently for every realm. Deploying the provider JAR alone does not configure an SMTP server or require a realm to replace its existing e-mail theme.

1. Open **Realm settings → Email** and configure the realm SMTP server: host, port, sender address, sender display name, encryption, and authentication when required by the server.
2. Use Keycloak's **Test connection** and **Test authentication** actions where they are available, then send a test e-mail to a realm user.
3. Choose one template integration:
   - for a quick start, select `access-requests` as the realm's **Email theme** in **Realm settings → Themes**;
   - to preserve an existing customer e-mail theme, download the **Email theme bundle** for the same provider version from the [GitHub Releases](https://github.com/4nass/keycloak-access-requests/releases) page and follow its README to copy the FTL templates and merge the matching `messages_*.properties` keys into that selected theme; or
   - make a customer e-mail theme inherit from `access-requests` when its existing inheritance chain permits it.
4. Enable [localization](#localization) and select the supported locales when localized e-mails are required.

Keycloak resolves e-mail templates from the realm's selected e-mail theme and its single parent chain; it has no automatic multi-theme composition. The selected theme must therefore expose the four access-request HTML/text templates and their message keys. If the template integration or SMTP configuration is missing, the access-request transaction still succeeds: the notification outbox retries delivery in the background and eventually records the entry as `FAILED`. Use the Email notifications tab to inspect and replay failed deliveries; Keycloak logs remain the source for delivery error diagnostics.

Use **Access requests → Email notifications** in the bundled Admin Console to see fixed
delivery-state counters, inspect failed rows without exposing recipient e-mail addresses, and
manually requeue a failed delivery. The same information is available to an authenticated
monitoring client through GET /admin/notification-deliveries/summary. Keycloak's own management
metrics remain configured separately with --metrics-enabled=true; the extension does not rely on
an internal Keycloak metrics SPI.

## Configure the API audience

All requester and approver endpoints require a bearer token with the `access-requests-api` audience. This prevents an arbitrary token issued by the realm from calling the provider API.

For each realm that has an API consumer:

1. Create an OIDC client named `access-requests-api`. It represents the API and does not need browser login flows.
2. Create a client scope also named `access-requests-api`.
3. Add an **Audience** mapper to the scope:
   - **Included Client Audience**: `access-requests-api`
   - **Add to access token**: enabled
4. Add the scope as a **Default** client scope to every client allowed to call the API.
5. Obtain a new access token and verify that its `aud` claim contains `access-requests-api`.

To remove an API client's access, remove this client scope from that client. Existing tokens remain usable until they expire; newly issued tokens no longer contain the required audience.

The Admin catalog endpoints follow Keycloak's administration authorization model instead. They do not use the API audience.

## Delegate access-request operations

Catalog changes, including access packages, risk, automatic approval, and publication, require a realm administrator. The `manage-access-requests` role does not permit these changes.

Operational administration has two boundaries:

1. The actor must be a Keycloak administrator for the target realm.
2. Unless the actor is a realm administrator, the actor must hold the target realm role `manage-access-requests`.

`realm-management:realm-admin` and the master realm's full `admin` role can also administer the catalog. `manage-realm` and `manage-users` alone do not grant access-request management.

For a least-privilege delegation:

1. Create the realm role `manage-access-requests`.
2. Create a group such as `access-request-managers`.
3. Assign `manage-access-requests` and the minimum Keycloak administration role needed to enter the target realm's Admin Console, normally `realm-management:view-realm`, to that group.
4. Assign operational managers to the group.

These managers can handle requests, audit events, notification deliveries, and provisioning or revocation failures, but cannot change the catalog or assurance policy.

## Configure approvers

Every entitlement references one **realm role** as its approver role. A user may approve an entitlement only when they have that role effectively assigned in the same realm.

Create dedicated roles such as `finance-approver` or `production-approver`, map them to groups where appropriate, then select them when creating an entitlement. Client roles cannot be used as approver roles.

An approver cannot approve their own request, even if they hold the entitlement's approver role. The server enforces this rule; hiding an action in the UI is not the security boundary.

## Configure approval assurance for HIGH and CRITICAL

Access-grant durations remain configurable **per entitlement**. Authentication assurance is a separate **per-realm** policy, editable only by a realm administrator in **Access requests → Approval assurance**. The defaults require ACR `2` / LoA 2, with a maximum age of 30 minutes for HIGH and 5 minutes for CRITICAL. HIGH can be tightened or extended up to 60 minutes; CRITICAL can only be tightened below 5 minutes. Both levels must be at least LoA 2, CRITICAL cannot be lower than HIGH, and CRITICAL cannot have a longer freshness window than HIGH.

Before approving HIGH or CRITICAL requests:

1. Configure the realm's browser authentication flow with a **Condition - Level of Authentication** at the selected LoA and a real MFA step (for example OTP or WebAuthn). Its Keycloak **Max Age** must be greater than zero and no greater than the extension's freshness limit for that risk level. Keycloak's documented `Max Age = 0` mode does not retain a level-specific timestamp; a longer flow Max Age could reuse assurance already stale by the extension's policy. Both configurations return `503 ASSURANCE_NOT_CONFIGURED` rather than sending the approver into a step-up loop. With the default HIGH and CRITICAL policies sharing LoA 2, configure that flow's Max Age to at most **300 seconds**; HIGH will then also require a fresh LoA after 300 seconds. Use separate LoAs if the two levels need distinct effective windows.
2. If using a named ACR such as `strong`, map it to the selected LoA in **Realm settings → Login → ACR to LoA Mapping**. The extension also supports a numeric ACR matching the LoA (for example `2`). Account Console and any other API client must use compatible effective ACR mappings; a client-specific mapping may override the realm mapping.
3. Test the browser flow with a real approver: request that ACR, verify that MFA is actually required, and verify that an access token issued afterward contains the expected ACR. Merely naming a level `2` does **not** prove that the flow performs MFA.
4. Set the corresponding ACR, LoA and freshness limit in **Access requests → Approval assurance**. Changes are recorded as Keycloak Admin Events when Admin Events are enabled for the realm.

The approval endpoint checks the **current**, locked entitlement risk, the token ACR, the authenticated client session LoA and the original LoA timestamp. A higher LoA may satisfy a lower requirement only when the token ACR maps to that higher level and its own timestamp is fresh; the shorter of the entitlement approval limit and the Keycloak flow Max Age applies. A missing or misaligned LoA condition returns `503 ASSURANCE_NOT_CONFIGURED`; an absent or stale proof returns `403 STEP_UP_REQUIRED` with the required ACR. The Account Console requests that ACR **without** `max_age=0`, allowing Keycloak to reuse the still-valid lower level and challenge only for the missing or expired higher level. It does not silently replay the approval: the approver must confirm it again after returning. Rejection does not require step-up.

The extension can validate Keycloak's LoA evidence, but it cannot prove that an arbitrary custom authentication flow actually used two independent factors. Realm administrators must review and test that flow before publishing HIGH or CRITICAL access packages. Without the required flow or evidence, approval fails closed.

## Localization

The themes include English, French, German, and Spanish messages. To make these selectable:

1. Open **Realm settings → Localization**.
2. Enable **Internationalization**.
3. Add `en`, `fr`, `de`, and `es` to **Supported locales**.
4. Select and save a default locale.

Keycloak chooses the locale from the user's choice, profile, OIDC `ui_locales` value, browser preference, and finally the realm default. Chinese is not packaged, so it falls back to the realm default or English.

Use **Realm overrides** only for deliberate realm-wide wording changes. An override for a message key changes the same key wherever the theme uses it.

## Operational checklist

Before making the catalog available to users, confirm:

- the provider is deployed;
- if the bundled console themes are selected, their pages have been verified with the realm's branding and extensions;
- if lifecycle e-mails are enabled, the realm SMTP connection and the selected e-mail-template integration have been tested;
- API clients receive the `access-requests-api` audience;
- catalog administrators have `realm-admin`; operational managers have the smallest necessary Keycloak admin role plus `manage-access-requests`;
- approver roles have been assigned to the intended users or groups;
- HIGH/CRITICAL ACR mappings, LoA flow, real MFA and freshness have been tested with an approver;
- each entitlement targets an existing role or group;
- only reviewed entitlements have `requestable=true`.
