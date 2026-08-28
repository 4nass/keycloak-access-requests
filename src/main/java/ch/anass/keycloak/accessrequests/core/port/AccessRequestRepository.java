package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;

import java.util.Optional;

public interface AccessRequestRepository {

    Optional<AccessRequest> findById(String realmId, String requestId);

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
