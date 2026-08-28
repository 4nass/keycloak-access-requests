package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;

import java.util.Optional;

public interface EntitlementRepository {

    Optional<Entitlement> findById(String realmId, String entitlementId);
}
