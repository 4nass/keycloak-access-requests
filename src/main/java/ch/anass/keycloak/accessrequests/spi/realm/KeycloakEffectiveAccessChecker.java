package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.Objects;

/**
 * Resolves effective access for the authenticated user in the current realm.
 */
final class KeycloakEffectiveAccessChecker implements EffectiveAccessChecker {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final UserModel user;

    KeycloakEffectiveAccessChecker(KeycloakSession session, RealmModel realm, UserModel user) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
    }

    @Override
    public boolean hasAccess(String realmId, String userId, Entitlement entitlement) {
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        if (!realm.getId().equals(realmId) || !user.getId().equals(userId)) {
            return false;
        }
        return switch (entitlement.resourceType()) {
            case REALM_ROLE, CLIENT_ROLE -> hasRole(entitlement);
            case GROUP -> isMemberOf(entitlement);
        };
    }

    private boolean hasRole(Entitlement entitlement) {
        RoleModel role = realm.getRoleById(entitlement.resourceId());
        return role != null && user.hasRole(role);
    }

    private boolean isMemberOf(Entitlement entitlement) {
        GroupModel group = session.groups().getGroupById(realm, entitlement.resourceId());
        return group != null && user.isMemberOf(group);
    }
}
