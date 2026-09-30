package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

import java.util.List;
import java.util.Objects;

/** Creates a dedicated root group; the caller must persist the binding in the same transaction. */
public final class KeycloakAccessPackageGroupFactory {

    private final KeycloakSession session;
    private final RealmModel realm;

    public KeycloakAccessPackageGroupFactory(KeycloakSession session, RealmModel realm) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
    }

    public AccessPackage create(String entitlementId, String realmId,
            List<AccessPackage.RoleMapping> mappings) {
        if (!realm.getId().equals(realmId)) {
            throw new IllegalArgumentException("The package must belong to the current realm");
        }
        String groupName = "AR_PKG_" + Objects.requireNonNull(entitlementId, "entitlementId must not be null");
        // Validate the complete selection before mutating the Keycloak group model.
        AccessPackage proposed = new AccessPackage(entitlementId, realmId, "pending", groupName, mappings);
        List<RoleModel> roles = proposed.roleMappings().stream().map(this::resolveRole).toList();

        GroupModel group = session.groups().createGroup(realm, groupName);
        if (group == null || group.getParent() != null || !groupName.equals(group.getName())) {
            throw new IllegalStateException("Keycloak did not create the expected root package group");
        }
        for (RoleModel role : roles) {
            group.grantRole(role);
        }
        return new AccessPackage(entitlementId, realmId, group.getId(), groupName, proposed.roleMappings());
    }

    private RoleModel resolveRole(AccessPackage.RoleMapping mapping) {
        RoleModel role = realm.getRoleById(mapping.roleId());
        if (role == null || role.isClientRole() != (mapping.type() == ResourceType.CLIENT_ROLE)) {
            throw new IllegalArgumentException("The selected package role is missing or has the wrong type");
        }
        return role;
    }
}
