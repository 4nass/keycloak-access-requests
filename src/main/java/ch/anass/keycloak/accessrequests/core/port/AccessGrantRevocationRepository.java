package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;

import java.util.Optional;

/** Persistence contract for a revocation attempt within one transaction. */
public interface AccessGrantRevocationRepository extends AccessGrantRepository {

    /** Lock the grant until the containing transaction completes, including on another node. */
    Optional<AccessGrant> findByRequestIdForUpdate(String realmId, String requestId);

    /** Update only if the version and revocation eligibility have not changed. */
    Optional<AccessGrant> updateIfVersionMatches(AccessGrant updated, long expectedVersion);
}
