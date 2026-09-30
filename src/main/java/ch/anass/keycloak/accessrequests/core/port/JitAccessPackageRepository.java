package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage;

import java.util.Optional;

public interface JitAccessPackageRepository {

    void create(JitAccessPackage accessPackage);

    Optional<JitAccessPackage> findByEntitlementId(String realmId, String entitlementId);
}
