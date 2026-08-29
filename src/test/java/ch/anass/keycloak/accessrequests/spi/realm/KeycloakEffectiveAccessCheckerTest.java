package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.lang.reflect.Proxy;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakEffectiveAccessCheckerTest {

    @Test
    void recognizesEffectiveRealmAndClientRoles() {
        RoleModel role = proxy(RoleModel.class, (proxy, method, arguments) -> null);
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "realm-1";
            case "getRoleById" -> role;
            default -> null;
        });
        UserModel user = proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "user-1";
            case "hasRole" -> arguments[0] == role;
            default -> null;
        });
        KeycloakEffectiveAccessChecker checker = new KeycloakEffectiveAccessChecker(
                proxy(KeycloakSession.class, (proxy, method, arguments) -> null), realm, user);

        assertTrue(checker.hasAccess("realm-1", "user-1", entitlement(ResourceType.REALM_ROLE, "role-1")));
        assertTrue(checker.hasAccess("realm-1", "user-1", entitlement(ResourceType.CLIENT_ROLE, "role-1")));
        assertFalse(checker.hasAccess("another-realm", "user-1", entitlement(ResourceType.REALM_ROLE, "role-1")));
        assertFalse(checker.hasAccess("realm-1", "another-user", entitlement(ResourceType.CLIENT_ROLE, "role-1")));
    }

    @Test
    void recognizesGroupMembership() {
        GroupModel group = proxy(GroupModel.class, (proxy, method, arguments) -> null);
        GroupProvider groups = proxy(GroupProvider.class, (proxy, method, arguments) ->
                method.getName().equals("getGroupById") ? group : null);
        KeycloakSession session = proxy(KeycloakSession.class, (proxy, method, arguments) ->
                method.getName().equals("groups") ? groups : null);
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) ->
                method.getName().equals("getId") ? "realm-1" : null);
        UserModel user = proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "user-1";
            case "isMemberOf" -> arguments[0] == group;
            default -> null;
        });

        KeycloakEffectiveAccessChecker checker = new KeycloakEffectiveAccessChecker(session, realm, user);

        assertTrue(checker.hasAccess("realm-1", "user-1", entitlement(ResourceType.GROUP, "group-1")));
    }

    private static Entitlement entitlement(ResourceType type, String resourceId) {
        return Entitlement.create(
                "entitlement-1",
                "realm-1",
                type,
                resourceId,
                "Finance Reader",
                "Read-only access to finance data.",
                RiskLevel.LOW,
                "access-request-approver",
                Instant.EPOCH).publish(Instant.EPOCH);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
