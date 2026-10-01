package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;

import java.util.Optional;

/** Persistence contract for a revocation attempt within one transaction. */
public interface AccessGrantRevocationRepository extends AccessGrantRepository {

    /** Lock the grant until the containing transaction completes, including on another node. */
    Optional<AccessGrant> findByRequestIdForUpdate(String realmId, String requestId);

    /**
     * Persist UNVERIFIED to AUTHORIZED or AUTHORIZED to REVOKED only when the version,
     * grant identity, and delivery group still match. The caller verifies revocation
     * authority while holding the grant lock; persistence enforces the state transition.
     */
    Optional<AccessGrant> updateIfVersionMatches(AccessGrant updated, long expectedVersion);
}
