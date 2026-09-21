# Realm configuration and authorization

Configure every realm independently. Entitlements, requests, approval permissions, and API authorization never cross realm boundaries.

## Enable the themes

1. Open **Realm settings → Themes**.
2. Select `access-requests` as the **Account theme**.
3. Select `access-requests` as the **Admin Console theme**.
4. Save the realm.

The Account theme exposes request and approval pages. The Admin theme exposes catalog administration only; it does not replace the server-side authorization checks.

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

## Delegate catalog administration

Catalog administration has two boundaries:

1. The actor must be a Keycloak administrator for the target realm.
2. Unless the actor is a realm administrator, the actor must hold the target realm role `manage-access-requests`.

`realm-management:realm-admin` and the master realm's full `admin` role bypass the dedicated role as full administrators. `manage-realm` and `manage-users` alone do not grant access-request catalog management.

For a least-privilege delegation:

1. Create the realm role `manage-access-requests`.
2. Create a group such as `access-request-managers`.
3. Assign `manage-access-requests` and the minimum Keycloak administration role needed to enter the target realm's Admin Console, normally `realm-management:view-realm`, to that group.
4. Assign catalog managers to the group.

This separation is intentional: a person can manage the access-request catalog without receiving broader realm-management permissions.

## Configure approvers

Every entitlement references one **realm role** as its approver role. A user may approve an entitlement only when they have that role effectively assigned in the same realm.

Create dedicated roles such as `finance-approver` or `production-approver`, map them to groups where appropriate, then select them when creating an entitlement. Client roles cannot be used as approver roles.

An approver cannot approve their own request, even if they hold the entitlement's approver role. The server enforces this rule; hiding an action in the UI is not the security boundary.

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

- the provider and both themes are deployed;
- API clients receive the `access-requests-api` audience;
- catalog managers have the smallest necessary Keycloak admin role plus `manage-access-requests`;
- approver roles have been assigned to the intended users or groups;
- each entitlement targets an existing role or group;
- only reviewed entitlements have `requestable=true`.
