package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationAuthority;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevoker;
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

    public AccessGrantRevocationService(AccessGrantRevocationRepository grants,
            AccessGrantRevocationAuthority authority, AccessGrantRevoker revoker,
            AccessRequestTransaction transaction, Clock clock) {
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
        this.authority = Objects.requireNonNull(authority, "authority must not be null");
        this.revoker = Objects.requireNonNull(revoker, "revoker must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void revokeExpired(String realmId, String requestId) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        transaction.execute(() -> {
            AccessGrant grant = grants.findByRequestIdForUpdate(realmId, requestId).orElse(null);
            if (grant != null && (!realmId.equals(grant.realmId()) || !requestId.equals(grant.requestId()))) {
                throw new IllegalStateException("Revocation repository returned a grant outside the requested scope");
            }
            if (grant == null || !grant.canAutoRevokeAt(Instant.now(clock))
                    || !authority.isExclusivelyManaged(grant)) {
                return null;
            }

            revoker.revoke(grant);
            grants.updateIfVersionMatches(grant.markRevoked(), grant.version())
                    .orElseThrow(() -> new ConcurrentGrantModificationException(requestId));
            return null;
        });
    }
}
