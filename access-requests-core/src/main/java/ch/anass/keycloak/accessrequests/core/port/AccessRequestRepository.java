package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;

import java.util.Optional;

public interface AccessRequestRepository {

    Optional<AccessRequest> findById(String realmId, String requestId);

    boolean existsPending(String realmId, String requesterId, String entitlementId);

    AccessRequest save(AccessRequest request);
}
