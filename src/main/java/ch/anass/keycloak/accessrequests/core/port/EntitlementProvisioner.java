package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningResult;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;

/**
 * Assigns a current entitlement to a requester in its target system.
 */
public interface EntitlementProvisioner {

    boolean supports(ResourceType resourceType);

    ProvisioningResult grant(String realmId, String requesterId, Entitlement entitlement);
}
