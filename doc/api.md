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

Each item contains its entitlement ID, resource type, display name, description, risk level, and flags indicating whether the user already has the access or has a pending request.

### `POST /requests`

```json
{
  "entitlementId": "a7e3761d-8f1f-4fbd-8c52-43d8e5c0e5c8",
  "justification": "I need read access to support the finance close."
}
```

`justification` must contain 10 to 2,000 characters. A successful submission returns `201 Created` and the request ID with its decision and provisioning status.

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
| `POST` | `/admin/entitlements` | Create a draft entitlement |
| `GET` | `/admin/entitlements/{entitlementId}` | Get one entitlement |
| `PUT` | `/admin/entitlements/{entitlementId}` | Update metadata and requestable state |

### `GET /admin/references`

This endpoint powers the Admin Console selectors. It accepts:

| Parameter | Requirement |
| --- | --- |
| `type` | Required: `REALM_ROLE`, `CLIENT_ROLE`, or `GROUP` |
| `search` | Optional name, description, or ID filter |
| `max` | Optional result cap, 1 to 100; default 50 |

It returns IDs for use as `resourceId` and `approverRoleId`. `approverRoleId` must refer to a realm role.

### `POST /admin/entitlements`

```json
{
  "resourceType": "REALM_ROLE",
  "resourceId": "1dcd1fa7-7ecb-469b-b827-8c06836bc27c",
  "displayName": "Finance reporting",
  "description": "Read access to finance reporting.",
  "riskLevel": "MEDIUM",
  "approverRoleId": "c91e7a3f-2e0f-4e87-b0f7-6bb74c431bd2"
}
```

The selected resource must exist and match `resourceType`. The approver role must exist in the same realm. Creation always produces a draft with `requestable=false` and returns `201 Created`. A duplicate resource in the same realm returns `409 Conflict`.

### `PUT /admin/entitlements/{entitlementId}`

```json
{
  "displayName": "Finance reporting",
  "description": "Read access to finance reporting.",
  "riskLevel": "MEDIUM",
  "approverRoleId": "c91e7a3f-2e0f-4e87-b0f7-6bb74c431bd2",
  "requestable": true,
  "version": 3
}
```

The resource type and resource ID are intentionally absent: the target resource is immutable. The client must send the version returned by the most recent read. A concurrent modification returns `409 Conflict`; reload the entitlement before retrying. Setting `requestable=false` is the supported soft-disable operation.

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
