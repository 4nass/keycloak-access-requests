package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeycloakJitPackageGroupFactoryTest {

    @Test
    void createsAnExtensionNamedRootGroupWithBothKindsOfRoleMapping() {
        Fixture fixture = new Fixture();
        JitAccessPackage accessPackage = fixture.factory().create("entitlement-1", "realm-1", List.of(
                new JitAccessPackage.RoleMapping(ResourceType.REALM_ROLE, "realm-role"),
                new JitAccessPackage.RoleMapping(ResourceType.CLIENT_ROLE, "client-role")));

        assertEquals("entitlement-1", accessPackage.entitlementId());
        assertEquals("group-1", accessPackage.groupId());
        assertEquals("AR_PKG_entitlement-1", accessPackage.groupName());
        assertEquals(List.of("realm-role", "client-role"), fixture.mappedRoleIds);
        assertEquals(1, fixture.createdGroups);
    }

    @Test
    void missingOrMismatchedRoleDoesNotCreateAnyGroup() {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.factory().create("entitlement-1", "realm-1",
                List.of(new JitAccessPackage.RoleMapping(ResourceType.REALM_ROLE, "missing"))));
        assertThrows(IllegalArgumentException.class, () -> fixture.factory().create("entitlement-1", "realm-1",
                List.of(new JitAccessPackage.RoleMapping(ResourceType.REALM_ROLE, "client-role"))));
        assertEquals(0, fixture.createdGroups);
    }

    @Test
    void rejectsCrossRealmCreationBeforeAnyMutation() {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.factory().create("entitlement-1", "other-realm",
                List.of(new JitAccessPackage.RoleMapping(ResourceType.REALM_ROLE, "realm-role"))));
        assertEquals(0, fixture.createdGroups);
    }

    private static final class Fixture {
        private final List<String> mappedRoleIds = new ArrayList<>();
        private int createdGroups;
        private final RoleModel realmRole = role("realm-role", false);
        private final RoleModel clientRole = role("client-role", true);

        KeycloakJitPackageGroupFactory factory() {
            RealmModel realm = proxy(RealmModel.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> "realm-1";
                case "getRoleById" -> switch ((String) args[0]) {
                    case "realm-role" -> realmRole;
                    case "client-role" -> clientRole;
                    default -> null;
                };
                default -> null;
            });
            GroupProvider groups = proxy(GroupProvider.class, (self, method, args) -> {
                if (!method.getName().equals("createGroup")) {
                    return null;
                }
                createdGroups++;
                String name = (String) args[1];
                return proxy(GroupModel.class, (group, call, values) -> switch (call.getName()) {
                    case "getId" -> "group-1";
                    case "getName" -> name;
                    case "getParent" -> null;
                    case "grantRole" -> {
                        mappedRoleIds.add(((RoleModel) values[0]).getId());
                        yield null;
                    }
                    default -> null;
                });
            });
            KeycloakSession session = proxy(KeycloakSession.class, (self, method, args) ->
                    method.getName().equals("groups") ? groups : null);
            return new KeycloakJitPackageGroupFactory(session, realm);
        }

        private static RoleModel role(String id, boolean clientRole) {
            return proxy(RoleModel.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> id;
                case "isClientRole" -> clientRole;
                default -> null;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
