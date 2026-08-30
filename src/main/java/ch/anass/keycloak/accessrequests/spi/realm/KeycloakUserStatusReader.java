package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.port.UserStatusReader;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Objects;

/**
 * Reads the enabled state of the authenticated user in the current realm.
 */
final class KeycloakUserStatusReader implements UserStatusReader {

    private final RealmModel realm;
    private final UserModel user;

    KeycloakUserStatusReader(RealmModel realm, UserModel user) {
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
    }

    @Override
    public boolean isEnabled(String realmId, String userId) {
        return realm.getId().equals(realmId) && user.getId().equals(userId) && user.isEnabled();
    }
}
