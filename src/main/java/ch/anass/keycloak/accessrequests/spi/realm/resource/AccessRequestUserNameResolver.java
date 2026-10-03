package ch.anass.keycloak.accessrequests.spi.realm.resource;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Objects;

/** Resolves presentation names without exposing Keycloak user IDs as labels. */
final class AccessRequestUserNameResolver {

    private final KeycloakSession session;
    private final RealmModel realm;

    AccessRequestUserNameResolver(KeycloakSession session, RealmModel realm) {
        this.session = Objects.requireNonNull(session);
        this.realm = Objects.requireNonNull(realm);
    }

    String resolve(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        UserModel user = session.users().getUserById(realm, userId);
        if (user == null) {
            return null;
        }
        String fullName = (nonBlank(user.getFirstName()) + " " + nonBlank(user.getLastName())).trim();
        return fullName.isEmpty() ? blankToNull(user.getUsername()) : fullName;
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        String trimmed = nonBlank(value);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
