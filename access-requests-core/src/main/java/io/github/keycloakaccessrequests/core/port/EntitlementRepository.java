package io.github.keycloakaccessrequests.core.port;

import io.github.keycloakaccessrequests.core.domain.Entitlement;

import java.util.Optional;

public interface EntitlementRepository {

    Optional<Entitlement> findById(String realmId, String entitlementId);
}
