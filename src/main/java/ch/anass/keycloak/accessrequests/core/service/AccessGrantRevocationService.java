package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationAuthority;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationFailureRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevoker;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantMembershipInspector;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** One expiration attempt; selecting due grants and scheduling attempts belong to the worker. */
public final class AccessGrantRevocationService {

    private final AccessGrantRevocationRepository grants;
    private final AccessGrantRevocationAuthority authority;
    private final AccessGrantRevoker revoker;
    private final AccessRequestTransaction transaction;
    private final Clock clock;
    private final AccessGrantRevocationFailureRepository failures;

    public AccessGrantRevocationService(AccessGrantRevocationRepository grants,
            AccessGrantRevocationAuthority authority, AccessGrantRevoker revoker,
            AccessRequestTransaction transaction, Clock clock) {
        this(grants, authority, revoker, transaction, clock, null);
    }

    public AccessGrantRevocationService(AccessGrantRevocationRepository grants,
            AccessGrantRevocationAuthority authority, AccessGrantRevoker revoker,
            AccessRequestTransaction transaction, Clock clock, AccessGrantRevocationFailureRepository failures) {
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
        this.authority = Objects.requireNonNull(authority, "authority must not be null");
        this.revoker = Objects.requireNonNull(revoker, "revoker must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.failures = failures;
    }

    public boolean revokeExpired(String realmId, String requestId) {
        return revokeExpired(realmId, requestId, true);
    }

    public boolean revokeExpired(String realmId, String requestId, boolean respectBackoff) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        return transaction.execute(() -> {
            AccessGrant grant = grants.findByRequestIdForUpdate(realmId, requestId).orElse(null);
            if (grant != null && (!realmId.equals(grant.realmId()) || !requestId.equals(grant.requestId()))) {
                throw new IllegalStateException("Revocation repository returned a grant outside the requested scope");
            }
            if (grant == null || !grant.canAutoRevokeAt(Instant.now(clock))) {
                return false;
            }
            if (respectBackoff && failures != null && failures.findOpen(realmId, requestId)
                    .filter(failure -> failure.nextAttemptAt().isAfter(Instant.now(clock))).isPresent()) {
                return false;
            }

            return removeLocked(grant);
        });
    }

    /** On-demand removal of an owned package grant, including one without an expiry. */
    public boolean revokeManually(String realmId, String requestId) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        return transaction.execute(() -> {
            AccessGrant grant = grants.findByRequestIdForUpdate(realmId, requestId).orElse(null);
            if (grant != null && (!realmId.equals(grant.realmId()) || !requestId.equals(grant.requestId()))) {
                throw new IllegalStateException("Revocation repository returned a grant outside the requested scope");
            }
            return grant != null && grant.canManuallyRevoke() && removeLocked(grant);
        });
    }

    private boolean removeLocked(AccessGrant grant) {
        if (!authority.isExclusivelyManaged(grant)) {
            throw new GrantRevocationAuthorityException();
        }
        try {
            revoker.revoke(grant);
        } catch (GrantRevocationAuthorityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GrantRevocationRemovalException(exception);
        }
        grants.updateIfVersionMatches(grant.markRevoked(), grant.version())
                .orElseThrow(() -> new ConcurrentGrantModificationException(grant.requestId()));
        if (failures != null) {
            failures.resolve(grant.realmId(), grant.requestId(), Instant.now(clock));
        }
        return true;
    }

    /** Administrative reconciliation: record success only when the historical membership is absent. */
    public boolean resolveExternallyRemoved(String realmId, String requestId,
            AccessGrantMembershipInspector inspector) {
        Objects.requireNonNull(inspector, "inspector must not be null");
        if (failures == null) {
            throw new IllegalStateException("Failure tracking is required for reconciliation");
        }
        return transaction.execute(() -> {
            if (failures.findOpen(realmId, requestId).isEmpty()) {
                return false;
            }
            AccessGrant grant = grants.findByRequestIdForUpdate(realmId, requestId).orElse(null);
            if (grant == null || grant.deliveryGroupId() == null
                    || (!grant.canAutoRevokeAt(Instant.now(clock)) && !grant.canManuallyRevoke())) {
                return false;
            }
            if (inspector.membership(grant) != AccessGrantMembershipInspector.Membership.ABSENT) {
                return false;
            }
            grants.updateIfVersionMatches(grant.markRevoked(), grant.version())
                    .orElseThrow(() -> new ConcurrentGrantModificationException(requestId));
            failures.resolve(realmId, requestId, Instant.now(clock));
            return true;
        });
    }
}
