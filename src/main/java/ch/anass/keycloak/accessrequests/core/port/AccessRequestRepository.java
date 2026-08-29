package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;

import java.util.Optional;
import java.util.Set;

public interface AccessRequestRepository {

    Optional<AccessRequest> findById(String realmId, String requestId);

    /**
     * Returns the entitlement identifiers with a pending request for the requester.
     */
    Set<String> findPendingEntitlementIds(String realmId, String requesterId, Set<String> entitlementIds);

    /**
     * Atomically persists a new request when no pending request exists for the
     * same realm, requester and entitlement.
     */
    Optional<AccessRequest> createIfNoPending(AccessRequest request);

    /**
     * Atomically persists an update when the request still has the expected
     * version. The returned request contains the new persisted version.
     */
    Optional<AccessRequest> updateIfVersionMatches(AccessRequest request, long expectedVersion);
}
