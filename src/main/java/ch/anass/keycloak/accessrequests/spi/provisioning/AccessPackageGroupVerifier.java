package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RoleModel;

import java.util.List;
import java.util.stream.Collectors;

/** Shared structural check for package provisioning and membership revocation. */
final class AccessPackageGroupVerifier {

    private AccessPackageGroupVerifier() {
    }

    static boolean matches(AccessPackage accessPackage, GroupModel group) {
        if (!accessPackage.groupId().equals(group.getId())
                || !accessPackage.groupName().equals(group.getName())
                || group.getParent() != null) {
            return false;
        }
        List<AccessPackage.RoleMapping> actualMappings = group.getRoleMappingsStream()
                .map(AccessPackageGroupVerifier::mapping)
                .toList();
        return actualMappings.size() == accessPackage.roleMappings().size()
                && actualMappings.stream().collect(Collectors.toSet())
                        .equals(accessPackage.roleMappings().stream().collect(Collectors.toSet()));
    }

    private static AccessPackage.RoleMapping mapping(RoleModel role) {
        return new AccessPackage.RoleMapping(
                role.isClientRole() ? ResourceType.CLIENT_ROLE : ResourceType.REALM_ROLE, role.getId());
    }
}
