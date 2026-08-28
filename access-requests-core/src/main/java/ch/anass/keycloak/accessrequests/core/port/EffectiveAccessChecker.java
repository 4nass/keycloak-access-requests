package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;

public interface EffectiveAccessChecker {

    boolean hasAccess(String realmId, String userId, Entitlement entitlement);
}
