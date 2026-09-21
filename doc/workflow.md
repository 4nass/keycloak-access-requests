# Workflow and entitlement model

## Entitlements

An entitlement is a realm-scoped, requestable access item. It has the following properties:

| Field | Meaning |
| --- | --- |
| Resource type | `REALM_ROLE`, `CLIENT_ROLE`, or `GROUP` |
| Resource | The immutable Keycloak role or group selected at creation |
| Display name and description | The text shown to requesters and approvers |
| Risk level | `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL` |
| Approver role | The realm role required to approve the entitlement |
| Requestable | Whether new requests are allowed |
| Version | The optimistic-lock version used when updating the entitlement |

An entitlement is unique per realm, resource type, and resource. Its target resource cannot be changed after creation; create a new entitlement instead when the target must change.

New entitlements are drafts (`requestable=false`). Setting `requestable=true` publishes the entitlement to the requester catalog. Setting it back to `false` stops new requests while preserving the entitlement and its history. There is no hard-delete endpoint.

Risk is currently a catalog and approval-queue classification. It does not alter the number of approvers or activate an automatic approval policy.

## Request lifecycle

```text
PENDING ── cancel by requester ──► CANCELED
   │
   ├── reject by authorized approver ──► REJECTED
   │
   └── approve by authorized approver ──► APPROVED
                                             │
                                             └── synchronous provisioning
                                                   ├── SUCCEEDED
                                                   └── FAILED
```

`decisionStatus` describes the business decision. `provisioningStatus` describes the result of applying an approved entitlement:

- a new request starts as `PENDING` / `NOT_STARTED`;
- a rejected or canceled request has no provisioning work;
- approval is final, and provisioning is attempted synchronously;
- a provisioning error leaves the decision as `APPROVED` and records `FAILED` so the outcome is auditable.

## Request rules

The service rejects a request when any of these conditions is true:

- the entitlement does not exist in the current realm;
- the entitlement is not requestable;
- the requester is disabled;
- the justification is missing, blank, or outside the configured size policy;
- the requester already has the selected role or group effectively granted;
- the requester already has a pending request for the same entitlement.

The database uniqueness constraint and transaction handling protect the last rule even when requests arrive concurrently.

Only the requester may cancel their own `PENDING` request. A completed request cannot be canceled, approved, or rejected again.

## Approval rules

An approval or rejection requires all of the following:

- the request is in the current realm and still `PENDING`;
- the entitlement is still requestable;
- the actor holds the entitlement's configured realm approver role;
- the actor is not the requester.

The approver role is checked on the server for every decision. The **Approvals** navigation item and queue are only convenience and discoverability features; they never grant authorization.

## Provisioning behavior

After an approval, the provider performs one synchronous, idempotent Keycloak operation:

- grant a realm role;
- grant a client role; or
- join a group.

If the user already has the resource, the operation succeeds without duplicating it. If the target user, role, or group no longer exists, provisioning fails and the request history records the failure. The provider does not yet retry failed provisioning automatically or revoke access after approval.

## Audit history

The provider records immutable request events for creation, cancellation, approval, rejection, provisioning start, provisioning success, and provisioning failure. It also records entitlement creation and updates with a snapshot of the configured fields.

These history records are for traceability. They do not replace Keycloak event logging, database backups, or an organization-wide audit retention policy.
