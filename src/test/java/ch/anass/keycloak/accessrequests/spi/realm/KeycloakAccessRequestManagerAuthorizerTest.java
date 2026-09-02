package ch.anass.keycloak.accessrequests.spi.realm;

import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakAccessRequestManagerAuthorizerTest {

    private final KeycloakAccessRequestManagerAuthorizer authorizer = new KeycloakAccessRequestManagerAuthorizer();

    @Test
    void authorizesOnlyUsersWithTheDedicatedRealmRole() {
        RoleModel managerRole = proxy(RoleModel.class, (proxy, method, arguments) -> null);
        RealmModel realm = realmWith(managerRole);
        UserModel manager = userWith(managerRole);
        UserModel userManager = userWith(proxy(RoleModel.class, (proxy, method, arguments) -> null));

        assertTrue(authorizer.canManage(realm, manager));
        assertFalse(authorizer.canManage(realm, userManager));
    }

    @Test
    void deniesAccessWhenTheDedicatedRoleDoesNotExistInTheRealm() {
        RealmModel realm = realmWith(null);
        UserModel user = userWith(proxy(RoleModel.class, (proxy, method, arguments) -> null));

        assertFalse(authorizer.canManage(realm, user));
    }

    private static RealmModel realmWith(RoleModel managerRole) {
        return proxy(RealmModel.class, (proxy, method, arguments) ->
                method.getName().equals("getRole")
                        && KeycloakAccessRequestManagerAuthorizer.ROLE_NAME.equals(arguments[0])
                        ? managerRole
                        : null);
    }

    private static UserModel userWith(RoleModel role) {
        return proxy(UserModel.class, (proxy, method, arguments) ->
                method.getName().equals("hasRole") && arguments[0] == role);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
