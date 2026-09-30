package ch.anass.keycloak.accessrequests.core.domain.entitlement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessPackageTest {

    @Test
    void bindsOneEntitlementToAnExtensionGroupAndMultipleRoleMappings() {
        AccessPackage accessPackage = new AccessPackage("entitlement-1", "realm-1", "group-1",
                "AR_PKG_REPORTING", List.of(
                        new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "realm-role-1"),
                        new AccessPackage.RoleMapping(ResourceType.CLIENT_ROLE, "client-role-1")));

        assertEquals("group-1", accessPackage.groupId());
        assertEquals("AR_PKG_REPORTING", accessPackage.groupName());
        assertEquals(2, accessPackage.roleMappings().size());
        assertEquals("entitlement-1", accessPackage.entitlementId());
        assertEquals("realm-1", accessPackage.realmId());
    }

    @Test
    void rejectsAGroupNameOutsideTheReservedNamespace() {
        assertThrows(IllegalArgumentException.class, () -> new AccessPackage("entitlement-1", "realm-1",
                "business-group", "Finance", List.of(
                        new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "role-1"))));
    }

    @Test
    void rejectsGroupCloningAndEmptyOrDuplicateRoleMappings() {
        assertThrows(IllegalArgumentException.class, () -> new AccessPackage.RoleMapping(
                ResourceType.GROUP, "source-group"));
        assertThrows(IllegalArgumentException.class, () -> new AccessPackage("entitlement-1", "realm-1",
                "group-1", "AR_PKG_EMPTY", List.of()));
        AccessPackage.RoleMapping role = new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "role-1");
        assertThrows(IllegalArgumentException.class, () -> new AccessPackage("entitlement-1", "realm-1",
                "group-1", "AR_PKG_DUPLICATE", List.of(role, role)));
    }

    @Test
    void rejectsUnusableIdentifiersAndCannotBeMutatedViaInputList() {
        List<AccessPackage.RoleMapping> roles = new java.util.ArrayList<>(List.of(
                new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "role-1")));
        AccessPackage accessPackage = new AccessPackage("entitlement-1", "realm-1", "group-1",
                "AR_PKG_ONE", roles);
        roles.clear();
        assertEquals(1, accessPackage.roleMappings().size());
        assertThrows(UnsupportedOperationException.class, () -> accessPackage.roleMappings().clear());
        assertThrows(IllegalArgumentException.class, () -> new AccessPackage(" ", "realm-1",
                "group-1", "AR_PKG_ONE", accessPackage.roleMappings()));
        assertThrows(IllegalArgumentException.class, () -> new AccessPackage.RoleMapping(
                ResourceType.REALM_ROLE, " "));
    }
}
