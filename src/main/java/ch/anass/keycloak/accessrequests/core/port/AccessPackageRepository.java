package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;

import java.util.Optional;

public interface AccessPackageRepository {

    void create(AccessPackage accessPackage);

    Optional<AccessPackage> findByEntitlementId(String realmId, String entitlementId);
}
