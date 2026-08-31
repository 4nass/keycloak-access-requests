package ch.anass.keycloak.accessrequests.core.port;

/**
 * Resolves whether an actor has a role in a realm.
 */
public interface RoleMembershipReader {

    boolean hasRole(String realmId, String actorId, String roleId);
}
