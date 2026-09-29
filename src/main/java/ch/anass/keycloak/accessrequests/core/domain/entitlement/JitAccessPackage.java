package ch.anass.keycloak.accessrequests.core.domain.entitlement;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A logical catalog entry delivered through a dedicated Keycloak group.
 *
 * <p>The group identifier must come from a group created by the extension and stored with the package.
 * The name prefix is a convention, not evidence of ownership or a permission boundary.
 */
public record JitAccessPackage(
        String entitlementId,
        String realmId,
        String groupId,
        String groupName,
        List<RoleMapping> roleMappings) {

    public JitAccessPackage {
        entitlementId = requireText(entitlementId, "entitlementId");
        realmId = requireText(realmId, "realmId");
        groupId = requireText(groupId, "groupId");
        groupName = requireText(groupName, "groupName");
        if (!groupName.startsWith("AR_PKG_") || groupName.length() == "AR_PKG_".length()) {
            throw new IllegalArgumentException("A JIT package group must use the AR_PKG_ namespace");
        }
        roleMappings = List.copyOf(Objects.requireNonNull(roleMappings, "roleMappings must not be null"));
        if (roleMappings.isEmpty() || Set.copyOf(roleMappings).size() != roleMappings.size()) {
            throw new IllegalArgumentException("A JIT package needs distinct role mappings");
        }
    }

    public record RoleMapping(ResourceType type, String roleId) {

        public RoleMapping {
            type = Objects.requireNonNull(type, "type must not be null");
            if (type == ResourceType.GROUP) {
                throw new IllegalArgumentException("A JIT package maps roles, not source groups");
            }
            roleId = requireText(roleId, "roleId");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
