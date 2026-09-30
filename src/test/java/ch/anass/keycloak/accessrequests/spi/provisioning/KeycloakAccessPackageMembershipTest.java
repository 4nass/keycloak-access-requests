package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeycloakAccessPackageMembershipTest {

    @Test
    void joinsOnlyTheDedicatedGroupEvenWhenTheUserHasTheSameRoleElsewhere() {
        Fixture fixture = new Fixture();
        fixture.roleAlreadyEffective = true;

        var result = fixture.membership().grant("realm-1", "user-1", fixture.accessPackage());

        assertEquals(ProvisioningStatus.SUCCEEDED, result.status());
        assertEquals(GrantOrigin.CREATED_BY_EXTENSION, result.grantOrigin());
        assertEquals(1, fixture.joins);
        assertEquals(0, fixture.directRoleGrants);
    }

    @Test
    void existingGroupMembershipIsNotClaimedAsExtensionOwned() {
        Fixture fixture = new Fixture();
        fixture.alreadyMember = true;

        var result = fixture.membership().grant("realm-1", "user-1", fixture.accessPackage());

        assertEquals(GrantOrigin.PREEXISTING, result.grantOrigin());
        assertEquals(0, fixture.joins);
    }

    @Test
    void rejectsAChangedGroupNameOrRoleMappingsBeforeJoining() {
        Fixture fixture = new Fixture();
        fixture.groupName = "business-group";
        assertEquals(ProvisioningFailureCode.RESOURCE_TYPE_MISMATCH,
                fixture.membership().grant("realm-1", "user-1", fixture.accessPackage()).failureCode());
        assertEquals(0, fixture.joins);

        fixture.groupName = "AR_PKG_REPORTING";
        fixture.groupRoles.removeLast();
        assertEquals(ProvisioningFailureCode.RESOURCE_TYPE_MISMATCH,
                fixture.membership().grant("realm-1", "user-1", fixture.accessPackage()).failureCode());
        assertEquals(0, fixture.joins);

        fixture.groupRoles.add(fixture.clientRole);
        fixture.groupRoles.add(fixture.extraRole);
        assertEquals(ProvisioningFailureCode.RESOURCE_TYPE_MISMATCH,
                fixture.membership().grant("realm-1", "user-1", fixture.accessPackage()).failureCode());
        assertEquals(0, fixture.joins);
    }

    @Test
    void failsClosedForMissingGroupUserOrWrongRealm() {
        Fixture fixture = new Fixture();
        fixture.groupExists = false;
        assertEquals(ProvisioningFailureCode.RESOURCE_MISSING,
                fixture.membership().grant("realm-1", "user-1", fixture.accessPackage()).failureCode());

        fixture.groupExists = true;
        fixture.userExists = false;
        assertEquals(ProvisioningFailureCode.REQUESTER_MISSING,
                fixture.membership().grant("realm-1", "user-1", fixture.accessPackage()).failureCode());

        fixture.userExists = true;
        assertEquals(ProvisioningFailureCode.REALM_MISMATCH,
                fixture.membership().grant("another-realm", "user-1", fixture.accessPackage()).failureCode());
        assertEquals(0, fixture.joins);
    }

    @Test
    void rejectsAGroupMovedUnderAParentThatCouldAddInheritedAccess() {
        Fixture fixture = new Fixture();
        fixture.nestedGroup = true;

        assertEquals(ProvisioningFailureCode.RESOURCE_TYPE_MISMATCH,
                fixture.membership().grant("realm-1", "user-1", fixture.accessPackage()).failureCode());
        assertEquals(0, fixture.joins);
    }

    private static final class Fixture {
        private boolean groupExists = true;
        private boolean userExists = true;
        private boolean alreadyMember;
        private boolean roleAlreadyEffective;
        private boolean nestedGroup;
        private String groupName = "AR_PKG_REPORTING";
        private int joins;
        private int directRoleGrants;
        private final RoleModel realmRole = role("realm-role-1", false);
        private final RoleModel clientRole = role("client-role-1", true);
        private final RoleModel extraRole = role("extra-role", false);
        private final List<RoleModel> groupRoles = new ArrayList<>(List.of(realmRole, clientRole));

        AccessPackage accessPackage() {
            return new AccessPackage("entitlement-1", "realm-1", "group-1", "AR_PKG_REPORTING", List.of(
                    new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "realm-role-1"),
                    new AccessPackage.RoleMapping(ResourceType.CLIENT_ROLE, "client-role-1")));
        }

        KeycloakAccessPackageMembership membership() {
            RealmModel realm = proxy(RealmModel.class, (self, method, args) ->
                    method.getName().equals("getId") ? "realm-1" : null);
            GroupModel group = proxy(GroupModel.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> "group-1";
                case "getName" -> groupName;
                case "getRoleMappingsStream" -> groupRoles.stream();
                case "getParent" -> nestedGroup ? proxy(GroupModel.class, (parent, call, values) -> null) : null;
                default -> null;
            });
            UserModel user = proxy(UserModel.class, (self, method, args) -> switch (method.getName()) {
                case "isMemberOf" -> alreadyMember;
                case "hasRole" -> roleAlreadyEffective;
                case "joinGroup" -> {
                    joins++;
                    yield null;
                }
                case "grantRole" -> {
                    directRoleGrants++;
                    yield null;
                }
                default -> null;
            });
            UserProvider users = proxy(UserProvider.class, (self, method, args) ->
                    method.getName().equals("getUserById") && userExists ? user : null);
            GroupProvider groups = proxy(GroupProvider.class, (self, method, args) ->
                    method.getName().equals("getGroupById") && groupExists ? group : null);
            KeycloakSession session = proxy(KeycloakSession.class, (self, method, args) -> switch (method.getName()) {
                case "users" -> users;
                case "groups" -> groups;
                default -> null;
            });
            return new KeycloakAccessPackageMembership(session, realm);
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
