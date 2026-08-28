package io.github.keycloakaccessrequests.core.port;

import io.github.keycloakaccessrequests.core.domain.AccessRequest;

import java.util.Optional;

public interface AccessRequestRepository {

    Optional<AccessRequest> findById(String realmId, String requestId);

    boolean existsPending(String realmId, String requesterId, String entitlementId);

    AccessRequest save(AccessRequest request);
}
