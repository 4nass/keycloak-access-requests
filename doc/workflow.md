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
| Duration policy | Default and maximum request duration, and whether permanent access is allowed |
| Version | The optimistic-lock version used when updating the entitlement |

An entitlement is unique per realm, resource type, and resource. Its target resource cannot be changed after creation; create a new entitlement instead when the target must change.

New entitlements are drafts (`requestable=false`). Setting `requestable=true` publishes the entitlement to the requester catalog. Setting it back to `false` stops new requests while preserving the entitlement and its history. There is no hard-delete endpoint.

The initial duration policy depends on risk: LOW 30/90 days, MEDIUM 7/30 days, HIGH 8/24 hours, and CRITICAL 1/4 hours (default/maximum). Administrators can change these values for each entitlement. Permanent access is disabled by default and must be allowed explicitly. Changing a policy affects new requests only; the duration selected on an existing request remains recorded.

### Separate birthright and just-in-time access

Do not publish a structural (birthright) role or group managed by HR, LDAP, AD, or another synchronization process as a just-in-time entitlement. Create a separate Keycloak resource dedicated to temporary access, such as `database-admin-jit`, and publish that resource instead. The external system keeps ownership of its structural mappings; the extension manages only its dedicated JIT mappings. Publishing an existing shared resource does **not** make it exclusively managed by the extension.

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
- the selected finite duration is not positive or exceeds the entitlement's maximum, or permanent access was not allowed.

The database uniqueness constraint and transaction handling protect the one-pending-request rule even when requests arrive concurrently.

The requester may select a finite duration or, when configured, permanent access. Omitting a duration uses the entitlement's default. This increment records and validates the selected duration; it does **not yet** schedule expiration or revoke granted access. Do not rely on the recorded duration as an enforcement deadline until the grant-lifecycle and revocation work is delivered.

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

If the user already has the resource, the operation succeeds without duplicating it. If the target user, role, or group no longer exists, provisioning fails and the request history records the failure. A manager can retry a failed approved request from the Admin Console or protected API. Failed provisioning is not retried automatically, and approval does not create a revocation workflow.

A successful operation records an `AccessGrant` with either `CREATED_BY_EXTENSION` or `PREEXISTING` origin. This is historical provenance, not durable proof that the current Keycloak mapping still belongs to the extension: an administrator or synchronization process may remove it and later reassign the same resource. New and existing grants therefore have `UNVERIFIED` revocation state and cannot authorize automatic removal. When an external change is detected, the grant can be invalidated with optimistic locking; an invalidated grant cannot be automatically revoked. The current release does not detect every external modification or expose a revocation action. A future expiration worker must require explicit, verified exclusive management of the JIT resource and an eligible grant state; otherwise it must leave the mapping untouched for manual reconciliation.

## Audit history

The provider records immutable request events for creation, cancellation, approval, rejection, provisioning start, provisioning success, and provisioning failure. It also records entitlement creation and updates with a snapshot of the configured fields.

These history records are for traceability. They do not replace Keycloak event logging, database backups, or an organization-wide audit retention policy.

## Lifecycle notifications

The provider queues localized e-mails when a request is submitted, approved, rejected, or cannot be provisioned. Recipient-specific queue entries are committed with the corresponding request and audit data; SMTP delivery happens asynchronously after commit. A temporary e-mail failure is retried automatically, while permanently undeliverable recipients are discarded without affecting the access-request workflow.

The queue is idempotent per lifecycle event, notification type, and recipient. Delivery is durable and at least once: in the narrow failure window after an SMTP server accepts a message and before the database records it as delivered, a retry may create a duplicate e-mail.
