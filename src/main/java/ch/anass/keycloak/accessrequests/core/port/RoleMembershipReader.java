package ch.anass.keycloak.accessrequests.core.port;

import java.util.Set;

/**
 * Resolves effective role membership for an actor in a realm.
 */
public interface RoleMembershipReader {

    boolean hasRole(String realmId, String actorId, String roleId);

    Set<String> findEffectiveRoleIds(String realmId, String actorId);
}
