package ch.anass.keycloak.accessrequests.spi.realm;

import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakRoleMembershipReaderTest {

    @Test
    void recognizesTheAuthenticatedActorsEffectiveMembershipInTheCurrentRealm() {
        RoleModel approverRole = proxy(RoleModel.class, (proxy, method, arguments) -> null);
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "realm-1";
            case "getRoleById" -> "finance-approver".equals(arguments[0]) ? approverRole : null;
            default -> null;
        });
        UserModel user = proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "approver-1";
            case "hasRole" -> arguments[0] == approverRole;
            default -> null;
        });
        KeycloakRoleMembershipReader reader = new KeycloakRoleMembershipReader(realm, user);

        assertTrue(reader.hasRole("realm-1", "approver-1", "finance-approver"));
        assertFalse(reader.hasRole("another-realm", "approver-1", "finance-approver"));
        assertFalse(reader.hasRole("realm-1", "another-user", "finance-approver"));
        assertFalse(reader.hasRole("realm-1", "approver-1", "unknown-role"));
    }

    @Test
    void resolvesEffectiveRoleIdentifiersOnlyForTheAuthenticatedActorInTheCurrentRealm() {
        RoleModel approverRole = proxy(RoleModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "finance-approver";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "isComposite" -> false;
            case "getCompositesStream" -> Stream.empty();
            default -> null;
        });
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "realm-1";
            default -> null;
        });
        UserModel user = proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "approver-1";
            case "getRoleMappingsStream" -> Stream.of(approverRole);
            case "getGroupsStream" -> Stream.empty();
            default -> null;
        });
        KeycloakRoleMembershipReader reader = new KeycloakRoleMembershipReader(realm, user);

        assertEquals(Set.of("finance-approver"), reader.findEffectiveRoleIds("realm-1", "approver-1"));
        assertEquals(Set.of(), reader.findEffectiveRoleIds("another-realm", "approver-1"));
        assertEquals(Set.of(), reader.findEffectiveRoleIds("realm-1", "another-user"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
