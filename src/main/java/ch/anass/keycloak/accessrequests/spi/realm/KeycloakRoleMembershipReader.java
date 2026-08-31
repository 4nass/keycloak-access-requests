package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.port.RoleMembershipReader;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.RoleUtils;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads effective realm role membership for the authenticated user.
 */
final class KeycloakRoleMembershipReader implements RoleMembershipReader {

    private final RealmModel realm;
    private final UserModel user;

    KeycloakRoleMembershipReader(RealmModel realm, UserModel user) {
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
    }

    @Override
    public boolean hasRole(String realmId, String actorId, String roleId) {
        if (!realm.getId().equals(realmId) || !user.getId().equals(actorId)) {
            return false;
        }
        RoleModel role = realm.getRoleById(roleId);
        return role != null && user.hasRole(role);
    }

    @Override
    public Set<String> findEffectiveRoleIds(String realmId, String actorId) {
        if (!realm.getId().equals(realmId) || !user.getId().equals(actorId)) {
            return Set.of();
        }
        return RoleUtils.getDeepUserRoleMappings(user).stream()
                .map(RoleModel::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
