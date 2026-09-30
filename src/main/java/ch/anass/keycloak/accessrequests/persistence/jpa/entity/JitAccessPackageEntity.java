package ch.anass.keycloak.accessrequests.persistence.jpa.entity;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage;
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
@Table(name = "AR_JIT_PACKAGE", uniqueConstraints = {
        @UniqueConstraint(name = "UK_JIT_PACKAGE_GROUP_ID", columnNames = "GROUP_ID"),
        @UniqueConstraint(name = "UK_JIT_PACKAGE_GROUP_NAME", columnNames = {"REALM_ID", "GROUP_NAME"})
})
public class JitAccessPackageEntity {

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
    @CollectionTable(name = "AR_JIT_PACKAGE_ROLE", joinColumns = @JoinColumn(name = "ENTITLEMENT_ID"),
            uniqueConstraints = @UniqueConstraint(name = "UK_JIT_PACKAGE_ROLE",
                    columnNames = {"ENTITLEMENT_ID", "ROLE_TYPE", "ROLE_ID"}))
    @OrderColumn(name = "MAPPING_ORDER", nullable = false)
    private List<RoleMappingValue> roleMappings = new ArrayList<>();

    protected JitAccessPackageEntity() {
    }

    private JitAccessPackageEntity(JitAccessPackage accessPackage) {
        entitlementId = accessPackage.entitlementId();
        realmId = accessPackage.realmId();
        groupId = accessPackage.groupId();
        groupName = accessPackage.groupName();
        roleMappings = accessPackage.roleMappings().stream().map(RoleMappingValue::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public static JitAccessPackageEntity from(JitAccessPackage accessPackage) {
        return new JitAccessPackageEntity(accessPackage);
    }

    public JitAccessPackage toDomain() {
        return new JitAccessPackage(entitlementId, realmId, groupId, groupName,
                roleMappings.stream().map(RoleMappingValue::toDomain).toList());
    }

    public String realmId() {
        return realmId;
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

        private RoleMappingValue(JitAccessPackage.RoleMapping mapping) {
            type = mapping.type();
            roleId = mapping.roleId();
        }

        private JitAccessPackage.RoleMapping toDomain() {
            return new JitAccessPackage.RoleMapping(type, roleId);
        }
    }
}
