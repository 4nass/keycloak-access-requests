package ch.anass.keycloak.accessrequests.persistence.jpa.entity;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "AR_ACCESS_PACKAGE", uniqueConstraints = {
        @UniqueConstraint(name = "UK_ACCESS_PACKAGE_GROUP_ID", columnNames = "GROUP_ID"),
        @UniqueConstraint(name = "UK_ACCESS_PACKAGE_GROUP_NAME", columnNames = {"REALM_ID", "GROUP_NAME"})
})
public class AccessPackageEntity {

    @Id
    @Column(name = "ENTITLEMENT_ID", nullable = false, length = 36)
    private String entitlementId;

    @Column(name = "REALM_ID", nullable = false, length = 255)
    private String realmId;

    @Column(name = "GROUP_ID", nullable = false, length = 255)
    private String groupId;

    @Column(name = "GROUP_NAME", nullable = false, length = 255)
    private String groupName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "AR_ACCESS_PACKAGE_ROLE", joinColumns = @JoinColumn(name = "ENTITLEMENT_ID"),
            uniqueConstraints = @UniqueConstraint(name = "UK_ACCESS_PACKAGE_ROLE",
                    columnNames = {"ENTITLEMENT_ID", "ROLE_TYPE", "ROLE_ID"}))
    @OrderColumn(name = "MAPPING_ORDER", nullable = false)
    private List<RoleMappingValue> roleMappings = new ArrayList<>();

    protected AccessPackageEntity() {
    }

    private AccessPackageEntity(AccessPackage accessPackage) {
        entitlementId = accessPackage.entitlementId();
        realmId = accessPackage.realmId();
        groupId = accessPackage.groupId();
        groupName = accessPackage.groupName();
        roleMappings = accessPackage.roleMappings().stream().map(RoleMappingValue::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public static AccessPackageEntity from(AccessPackage accessPackage) {
        return new AccessPackageEntity(accessPackage);
    }

    public AccessPackage toDomain() {
        return new AccessPackage(entitlementId, realmId, groupId, groupName,
                roleMappings.stream().map(RoleMappingValue::toDomain).toList());
    }

    public String realmId() {
        return realmId;
    }

    public void replaceRoleMappings(List<AccessPackage.RoleMapping> mappings) {
        AccessPackage replacement = new AccessPackage(entitlementId, realmId, groupId, groupName, mappings);
        roleMappings.clear();
        roleMappings.addAll(replacement.roleMappings().stream().map(RoleMappingValue::new).toList());
    }

    @Embeddable
    public static class RoleMappingValue {

        @Enumerated(EnumType.STRING)
        @Column(name = "ROLE_TYPE", nullable = false, length = 30)
        private ResourceType type;

        @Column(name = "ROLE_ID", nullable = false, length = 255)
        private String roleId;

        protected RoleMappingValue() {
        }

        private RoleMappingValue(AccessPackage.RoleMapping mapping) {
            type = mapping.type();
            roleId = mapping.roleId();
        }

        private AccessPackage.RoleMapping toDomain() {
            return new AccessPackage.RoleMapping(type, roleId);
        }
    }
}
