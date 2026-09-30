package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.grant.ProvisioningResult;

public interface AccessPackageProvisioner {

    ProvisioningResult grant(String realmId, String requesterId, AccessPackage accessPackage);
}
