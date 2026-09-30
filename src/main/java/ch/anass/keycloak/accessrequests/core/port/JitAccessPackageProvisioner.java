package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.grant.ProvisioningResult;

public interface JitAccessPackageProvisioner {

    ProvisioningResult grant(String realmId, String requesterId, JitAccessPackage accessPackage);
}
