package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence contract for a revocation attempt within one transaction. */
public interface AccessGrantRevocationRepository extends AccessGrantRepository {

    /**
     * Scan due, authorized, extension-owned package grants across realms in expiry/request ID order.
     * A null cursor pair starts a sweep; subsequent calls use both fields of the last returned grant.
     * The implementation must bound the page size to 1..100 and omit permanent, preexisting,
     * direct-mapping, unverified, invalidated, revoked, and future grants.
     */
    List<AccessGrant> findDuePackageGrants(Instant dueAt, Instant afterExpiry, String afterRequestId, int limit);

    /** Lock the grant until the containing transaction completes, including on another node. */
    Optional<AccessGrant> findByRequestIdForUpdate(String realmId, String requestId);

    /**
     * Persist UNVERIFIED to AUTHORIZED or AUTHORIZED to REVOKED only when the version,
     * grant identity, and delivery group still match. The caller verifies revocation
     * authority while holding the grant lock; persistence enforces the state transition.
     */
    Optional<AccessGrant> updateIfVersionMatches(AccessGrant updated, long expectedVersion);
}
