# REST API reference

## Base path and format

Every endpoint is scoped to one realm:

```text
{keycloak-base-url}/realms/{realm}/access-requests
```

Requests and responses use JSON unless an endpoint returns `204 No Content`. Identifiers in paths must be URL encoded by the client.

## Authentication and authorization

Requester and approver endpoints require:

- `Authorization: Bearer <access-token>`;
- an authenticated user in `{realm}`;
- the `access-requests-api` audience in the access token.

See [API audience configuration](configuration.md#configure-the-api-audience).

Admin endpoints use Keycloak administration authorization. The caller must be a Keycloak administrator in `{realm}` and, unless they are a realm administrator, must hold the `manage-access-requests` realm role. See [delegated catalog administration](configuration.md#delegate-catalog-administration).

## Pagination and filters

List endpoints return:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "total": 0
}
```

`page` is zero-based. The default `size` is 20 and the maximum is 100. A negative page, a size outside that range, an invalid enum, invalid date, or an excessive offset returns `400 Bad Request`.

## Requester and approver endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/catalog` | List requestable entitlements for the authenticated user |
| `POST` | `/requests` | Submit an access request |
| `GET` | `/mine` | List the caller's requests |
| `GET` | `/mine/{requestId}` | Get a request, its decision, and history for its owner |
| `POST` | `/{requestId}/cancel` | Cancel the caller's pending request |
| `GET` | `/pending` | List pending requests the caller may decide |
| `GET` | `/capabilities` | Return whether the caller can approve at least one entitlement |
| `POST` | `/{requestId}/approve` | Approve and synchronously provision a request |
| `POST` | `/{requestId}/reject` | Reject a request |

### `GET /catalog`

Optional query parameters:

| Parameter | Values |
| --- | --- |
| `type` | `REALM_ROLE`, `CLIENT_ROLE`, `GROUP` |
| `search` | Case-insensitive text filter |
| `riskLevel` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `page`, `size` | Pagination controls |

Each item contains its entitlement ID, resource type, display name, description, risk level, `defaultDurationSeconds`, `maxDurationSeconds`, `allowPermanent`, and flags indicating whether the user already has the access or has a pending request.

### `POST /requests`

```json
{
  "entitlementId": "a7e3761d-8f1f-4fbd-8c52-43d8e5c0e5c8",
  "justification": "I need read access to support the finance close.",
  "durationSeconds": 604800
}
```

`justification` must contain 10 to 2,000 characters. `durationSeconds` must be a positive integer no greater than the entitlement's maximum; omit it to use the current default. To request permanent access, send `"permanent": true` and omit `durationSeconds`; this is accepted only if `allowPermanent=true`. Invalid combinations return `400 Bad Request`. A successful submission returns `201 Created` and the request ID, decision and provisioning status, and selected duration/permanent flag. Requester and approver reads include that selection. For a temporary package grant owned by the extension, the expiry starts after successful provisioning and a background job revokes the package-group membership when due. A permanent package grant has no expiry and is excluded from the scheduler, but a manager can revoke its membership manually through the Admin Console.

The server returns `409 Conflict` when the entitlement is not requestable, the user already has the resource, or a request for the same entitlement is already pending.

### `GET /mine`

Optional filters:

| Parameter | Values |
| --- | --- |
| `status` | `PENDING`, `APPROVED`, `REJECTED`, `CANCELED` |
| `resourceType` | `REALM_ROLE`, `CLIENT_ROLE`, `GROUP` |
| `from`, `to` | ISO-8601 instants, for example `2026-09-22T10:15:30Z` |
| `page`, `size` | Pagination controls |

`GET /mine/{requestId}` returns `404 Not Found` when the request does not belong to the caller. This intentionally prevents cross-user request discovery.

### Decisions and cancellation

`POST /{requestId}/cancel` has no body and returns `204 No Content` on success.

Approval and rejection accept an optional comment:

```json
{
  "comment": "Approved for the current finance close."
}
```

The decision endpoints return `200 OK` with the updated request. Approval returns the final provisioning status after the synchronous Keycloak operation.

The server returns `403 Forbidden` when a caller tries to cancel another user's request, approve their own request, or decide without the entitlement's approver role. A stale or completed request returns `409 Conflict`.

## Catalog administration endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/admin/capabilities` | Confirm catalog-management access for the current admin |
| `GET` | `/admin/references` | Search selectable Keycloak roles or groups |
| `GET` | `/admin/entitlements` | List all entitlement drafts and published entitlements |
| `POST` | `/admin/entitlements` | Create an API-only direct draft; it cannot be published |
| `POST` | `/admin/access-packages` | Create a draft package with its dedicated group and role bindings |
| `GET` | `/admin/entitlements/{entitlementId}` | Get one entitlement |
| `GET` | `/admin/access-packages/{packageId}` | Inspect the bound group, roles, and configuration health |
| `PUT` | `/admin/entitlements/{entitlementId}` | Update metadata and requestable state |

### `GET /admin/references`

This endpoint powers the Admin Console selectors. It accepts:

| Parameter | Requirement |
| --- | --- |
| `type` | Required: `REALM_ROLE`, `CLIENT_ROLE`, or `GROUP` |
| `search` | Optional name/client ID/group-name search (at least two characters), or an exact resource ID |
| `selectedId` | Optional exact ID of an already selected resource, resolved when `search` has fewer than two characters |
| `first` | Optional search-result offset, default 0 |
| `max` | Optional result cap, 1 to 100; default 50 |

An empty or one-character `search` does not enumerate the realm; without `selectedId`, it returns an empty list. Text search uses Keycloak's paginated search APIs: realm-role names, client-role names or client IDs, and group names. The response contains `items`, `nextFirst`, and `hasMore`; pass `nextFirst` as `first` while `hasMore` is true. An exact-ID search returns that resource directly instead of starting a text-search page. `selectedId` only resolves an existing selection when no text search is active. Group-name matching is case-insensitive with Keycloak 26.7.4's built-in JPA provider; custom group storage providers may behave differently. Arbitrary substrings of IDs, descriptions, and full group paths are not searched; an exact ID lookup is supported instead. Results follow Keycloak's search order rather than a global sort performed by this extension. It returns IDs for use as `resourceId` and `approverRoleId`. `approverRoleId` must refer to a realm role.

### `POST /admin/entitlements`

```json
{
  "resourceType": "REALM_ROLE",
  "resourceId": "1dcd1fa7-7ecb-469b-b827-8c06836bc27c",
  "displayName": "Finance reporting",
  "description": "Read access to finance reporting.",
  "riskLevel": "MEDIUM",
  "approverRoleId": "c91e7a3f-2e0f-4e87-b0f7-6bb74c431bd2",
  "defaultDurationSeconds": 604800,
  "maxDurationSeconds": 2592000,
  "allowPermanent": false
}
```

The selected resource must exist and match `resourceType`. The approver role must exist in the same realm. The duration values are configurable per entitlement; omitting them on creation applies the risk-level defaults and `allowPermanent=false`. Creation always produces a draft with `requestable=false` and returns `201 Created`. Explicit `requestable=true` is rejected with `409 ACCESS_PACKAGE_REQUIRED` rather than silently ignored. A duplicate resource in the same realm returns `409 Conflict`. The Admin Console does not use this endpoint for creation: its single creation path is `POST /admin/access-packages`, which creates the entitlement as part of the package.

Direct role and group drafts cannot be made requestable. Temporary access is delivered only through a bound access package, so that expiry can remove the extension-owned group membership without touching rights managed elsewhere. New temporary grants without a delivery group are rejected at persistence, and missing grant authorization fails the provisioning transaction. Create a package with `POST /admin/access-packages` for new requestable access. A direct draft sent to `PUT` with `requestable=true` returns `409 ACCESS_PACKAGE_REQUIRED`.

### Access packages

`POST /admin/access-packages` accepts the same metadata and duration-policy fields as
entitlement creation, except for `resourceType` and `resourceId`. It requires `roleMappings`, an
array of 1–100 `{ "type": "REALM_ROLE" | "CLIENT_ROLE", "roleId": "..." }` entries. The server
creates a dedicated `AR_PKG_{entitlementId}` group, maps the selected roles to it, and persists
the binding with a draft GROUP entitlement in one transaction. The package ID is the ID of this
entitlement. The package defines the group and role composition; the entitlement defines the
catalog policy (`riskLevel`, approvers, duration, `allowPermanent`, and `requestable`). A grant
records an individual user's resulting access. The administrator must inspect the package and
publish its entitlement separately.

`GET /admin/access-packages/{packageId}` returns the group ID/name, `groupExists`,
`configurationValid`, and each bound role's type, ID, name, and `missing` flag. It returns 404 for
an entitlement without an access-package binding. Publishing an unbound entitlement or an invalid package through the
entitlement `PUT` returns 409;
unpublishing remains possible.

### `PUT /admin/entitlements/{entitlementId}`

```json
{
  "displayName": "Finance reporting",
  "description": "Read access to finance reporting.",
  "riskLevel": "MEDIUM",
  "approverRoleId": "c91e7a3f-2e0f-4e87-b0f7-6bb74c431bd2",
  "requestable": true,
  "defaultDurationSeconds": 604800,
  "maxDurationSeconds": 2592000,
  "allowPermanent": false,
  "version": 3
}
```

The resource type and resource ID are intentionally absent: the target resource is immutable. The client must send the version returned by the most recent read. Duration values must be positive whole seconds with default no greater than maximum. A concurrent modification returns `409 Conflict`; reload the entitlement before retrying. Setting `requestable=false` is the supported soft-disable operation.

Successful catalog creates and updates also emit Keycloak Admin Events with resource type
`ACCESS_REQUEST_ENTITLEMENT` and resource path `access-requests/entitlements/{id}`. The event
details contain the new `requestable`, `riskLevel`, `approverRoleId`, and duration-policy values, but not the full
entitlement representation. A soft-disable is an `UPDATE` event, not `DELETE`. Keycloak stores
these events only when Admin Events are enabled for the realm; the extension's own catalog
history is persisted regardless of that setting.

## Access request audit endpoints

These endpoints use the same realm-scoped administrator authorization as catalog management.
They read the existing request history; they do not duplicate it into Keycloak user events.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/admin/events` | Search access request lifecycle events |
| `GET` | `/admin/audit-users` | Search people for the audit filters |
| `GET` | `/admin/requests/{requestId}` | Read a request and its history |

`GET /admin/events` accepts `from` and `to` as inclusive ISO-8601 instants, `type` as a
request event type, exact `requesterId`, `actorId` and `requestId` filters, and `page`/`size` pagination.
`requesterId` selects every event on requests created by that person; `actorId` selects only events
they performed. Filtering happens before counting and pagination. The Admin Console resolves names to
IDs with `GET /admin/audit-users?search=...`. This lookup requires at least two characters, returns at
most 20 realm users per query, and exposes only ID, display name, and username to authorized managers.
Results are newest first and contain only event ID, request ID, type, actor ID, and timestamp.
Comments and other history metadata are not exposed in the list. Invalid filters return `400`.
The admin detail response includes the requester ID, current decision and provisioning statuses,
decision details, and a chronological history page (default `historyPage=0&historySize=20`,
maximum size 100). `historyPage`, `historySize`, and `historyTotal` accompany the `history`
array so clients can request subsequent pages without loading the entire history.
Each history entry includes its actor ID and timestamp. Failed provisioning entries
include only a stable failure code (unknown values become `UNKNOWN`), while closure entries
include the operator's closure reason. Raw failure comments and event metadata are never returned.
These administrative fields are not added to the requester's `/mine/{id}` response.
The detail endpoint returns `404` when
the request does not exist in the selected realm.

## Notification delivery administration endpoints

The notification endpoints are realm-scoped and use the same Keycloak administrator and
manage-access-requests authorization as catalog management.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /admin/notification-deliveries | List failed lifecycle e-mail deliveries |
| GET | /admin/notification-deliveries/summary | Return low-cardinality delivery counts by state |
| POST | /admin/notification-deliveries/{deliveryId}/retry | Put one failed delivery back in the retry queue |

The delivery list accepts the standard page and size parameters (default 0 and 20, maximum
size 100) and returns only terminal FAILED rows. A row contains request, entitlement, recipient
identifier, notification type, attempt count, and last-attempt time. It never returns a recipient
e-mail address.

The summary returns the fixed set of counters pending, processing, delivered, discarded, and
failed. These are intentionally low-cardinality operational metrics: they are safe to render in
the Admin Console or poll with an authenticated monitoring client. They are distinct from
Keycloak's platform management metrics endpoint.

The retry operation returns 204 No Content when it atomically requeues a row that remains FAILED.
Its attempt budget is reset so the worker can make up to ten new attempts. It returns 404 Not Found
for an ID outside the realm or absent from the outbox, and 409 Conflict when another worker or
administrator has already changed its state.

## Failed provisioning administration endpoints

These realm-scoped endpoints require the same administrator access and `manage-access-requests`
authorization as catalog management.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/admin/provisioning-failures?state=OPEN\|CLOSED&page=0&size=20` | List open failures (default) or closed failures, including closure date, actor, and reason in the archive; requires `manage-access-requests` |
| `POST` | `/admin/requests/{requestId}/provisioning/retry` | Retry the approved Keycloak grant |
| `POST` | `/admin/requests/{requestId}/provisioning/close` | Close an unrecoverable failure without granting access |

The list accepts `page` and `size` (defaults 0 and 20, maximum size 100) and returns the usual
`items`, `page`, `size`, and `total` envelope. Items contain request, requester, entitlement,
resource, status, update-time metadata, and the safe `failureCode` from the latest failed
attempt. New failures are ordered by the request version, not by their random event IDs.
Legacy or unrecognized diagnostics use `UNKNOWN`; tied legacy events without a version also
use `UNKNOWN` when their order cannot be established. The raw failure reason and requester
justification are not exposed. Invalid pagination returns `400 Bad Request`.

`failureCode` is one of `REQUESTER_MISSING`, `RESOURCE_MISSING`, `RESOURCE_TYPE_MISMATCH`,
`REALM_MISMATCH`, `PROVIDER_UNAVAILABLE`, `UNEXPECTED_FAILURE`, or `UNKNOWN`. It is intended for
localized operational guidance, not for reconstructing technical exception messages. The
existing `updatedAt` field is the time of the most recent failed attempt.

Retry returns `200 OK` with the resulting request and provisioning status, which can still be
`FAILED` when the grant fails again. Missing requests return `404 Not Found`; requests that are
no longer approved with failed provisioning, or concurrently changed requests, return
`409 Conflict`.

Closure accepts `{"reason":"..."}` with a mandatory 10–1000 character operational reason and
returns `200 OK` with the closure time, actor, and reason. It requires `manage-access-requests`.
Only an approved request with an open provisioning failure can be closed. The action leaves the
approval and failed provisioning state unchanged, records an immutable history event, removes
the request from the active failure queue, and permanently blocks retry. There is no hard delete.
Invalid reasons return `400`, missing or cross-realm requests `404`, and already closed or
concurrently changed requests `409`. The requester detail history exposes the closure event,
but not its internal reason. Requester list and detail responses expose `provisioningClosedAt`
so the Account Console can distinguish a closed failure from one still awaiting repair.
Closure e-mail is queued for the requester and the entitlement's
current approver role when the entitlement still exists; delivery to a deleted requester is
discarded. The reason is not included in e-mail.

## Package grant revocation

Managers with `manage-access-requests` can inspect a grant on
`GET /admin/requests/{requestId}`. The `grant` field contains its origin, revocation state,
expiry (omitted for permanent access), and a `manuallyRevocable` UI hint. The server checks
authorization and current package ownership again for every write.

`POST /admin/grants/{requestId}/revocation` accepts `{"reason":"..."}` (10–1000 characters).
It removes only an extension-owned, authorized package-group membership, including a permanent
one, and records the actor and reason in the request history. It returns `200` with `REVOKED`
or `FAILED`; an operational failure is also listed in
`GET /admin/revocation-failures?state=OPEN`. An invalid reason returns `400`, a missing grant
`404`, and an ineligible or already revoked grant `409`. A pre-existing membership is never
claimed for removal. Manual revocation of a temporary grant is allowed before expiry. A failed
manual removal of permanent access requires an operator-initiated retry or verified resolution;
the expiry scheduler never picks up permanent grants.

`POST /admin/grants/{requestId}/revocation/retry` retries an open failure, and
`POST /admin/grants/{requestId}/revocation/resolve` records an externally completed removal
only after the server verifies that the recorded package membership is absent. Both operations
remain available for a failed manual revocation of permanent access. Expiry alone never
revokes a permanent grant.

## Error handling

Authentication failures return `401 Unauthorized`; authorization failures return `403 Forbidden`; missing resources return `404 Not Found`; invalid submissions and queries return `400 Bad Request`; invalid state changes, duplicate resources, and concurrent updates return `409 Conflict`.

Domain errors returned by the realm resource use this shape where applicable:

```json
{
  "code": "CONCURRENT_MODIFICATION",
  "message": "The request was modified concurrently.",
  "requestId": "a7e3761d-8f1f-4fbd-8c52-43d8e5c0e5c8"
}
```

Treat `code` and the HTTP status as the integration contract. `message` is intended for diagnostics and may change; clients should not expose it directly to end users.
