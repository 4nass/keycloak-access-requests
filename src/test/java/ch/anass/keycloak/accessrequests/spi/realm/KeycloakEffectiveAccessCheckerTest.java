package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.JitAccessPackageRepository;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakEffectiveAccessCheckerTest {

    @Test
    void recognizesEffectiveRealmAndClientRoles() {
        RoleModel realmRole = role(false);
        RoleModel clientRole = role(true);
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "realm-1";
            case "getRoleById" -> switch ((String) arguments[0]) {
                case "realm-role" -> realmRole;
                case "client-role" -> clientRole;
                default -> null;
            };
            default -> null;
        });
        UserModel user = proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "user-1";
            case "hasRole" -> arguments[0] == realmRole || arguments[0] == clientRole;
            default -> null;
        });
        KeycloakEffectiveAccessChecker checker = new KeycloakEffectiveAccessChecker(
                proxy(KeycloakSession.class, (proxy, method, arguments) -> null), realm, user);

        assertTrue(checker.hasAccess("realm-1", "user-1", entitlement(ResourceType.REALM_ROLE, "realm-role")));
        assertTrue(checker.hasAccess("realm-1", "user-1", entitlement(ResourceType.CLIENT_ROLE, "client-role")));
        assertFalse(checker.hasAccess("realm-1", "user-1", entitlement(ResourceType.REALM_ROLE, "client-role")));
        assertFalse(checker.hasAccess("realm-1", "user-1", entitlement(ResourceType.CLIENT_ROLE, "realm-role")));
        assertFalse(checker.hasAccess("another-realm", "user-1", entitlement(ResourceType.REALM_ROLE, "realm-role")));
        assertFalse(checker.hasAccess("realm-1", "another-user", entitlement(ResourceType.CLIENT_ROLE, "client-role")));
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

    @Test
    void aSourceRoleFromAnotherSystemDoesNotCountAsJitPackageMembership() {
        RoleModel sourceRole = role(false);
        GroupModel deliveryGroup = proxy(GroupModel.class, (proxy, method, arguments) -> null);
        GroupProvider groups = proxy(GroupProvider.class, (proxy, method, arguments) ->
                method.getName().equals("getGroupById") && "jit-group-1".equals(arguments[1])
                        ? deliveryGroup : null);
        KeycloakSession session = proxy(KeycloakSession.class, (proxy, method, arguments) ->
                method.getName().equals("groups") ? groups : null);
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "realm-1";
            case "getRoleById" -> sourceRole;
            default -> null;
        });
        UserModel user = proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> "user-1";
            case "hasRole" -> true;
            case "isMemberOf" -> false;
            default -> null;
        });
        JitAccessPackage accessPackage = new JitAccessPackage("entitlement-1", "realm-1", "jit-group-1",
                "AR_PKG_example", List.of(new JitAccessPackage.RoleMapping(ResourceType.REALM_ROLE, "role-1")));
        JitAccessPackageRepository packages = new JitAccessPackageRepository() {
            @Override
            public void create(JitAccessPackage value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<JitAccessPackage> findByEntitlementId(String realmId, String entitlementId) {
                return Optional.of(accessPackage);
            }
        };
        KeycloakEffectiveAccessChecker checker = new KeycloakEffectiveAccessChecker(session, realm, user, packages);

        assertFalse(checker.hasAccess("realm-1", "user-1", entitlement(ResourceType.REALM_ROLE, "role-1")));
        assertFalse(checker.hasAccess("other-realm", "user-1", entitlement(ResourceType.REALM_ROLE, "role-1")));
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

    private static RoleModel role(boolean clientRole) {
        return proxy(RoleModel.class, (proxy, method, arguments) ->
                method.getName().equals("isClientRole") ? clientRole : null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
