package ch.anass.keycloak.accessrequests.spi.realm;

import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.Objects;

/**
 * Authorizes the dedicated realm role that manages access request configuration.
 */
final class KeycloakAccessRequestManagerAuthorizer {

    static final String ROLE_NAME = "manage-access-requests";

    boolean canManage(RealmModel realm, UserModel user) {
        Objects.requireNonNull(realm, "realm must not be null");
        Objects.requireNonNull(user, "user must not be null");
        RoleModel managerRole = realm.getRole(ROLE_NAME);
        return managerRole != null && user.hasRole(managerRole);
    }
}
