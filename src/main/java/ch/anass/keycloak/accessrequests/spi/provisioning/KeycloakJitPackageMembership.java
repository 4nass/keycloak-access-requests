package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.ProvisioningResult;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.port.JitAccessPackageProvisioner;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Assigns package membership, never the underlying roles directly. */
public final class KeycloakJitPackageMembership implements JitAccessPackageProvisioner {

    private static final Logger LOG = Logger.getLogger(KeycloakJitPackageMembership.class.getName());

    private final KeycloakSession session;
    private final RealmModel realm;

    public KeycloakJitPackageMembership(KeycloakSession session, RealmModel realm) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
    }

    @Override
    public ProvisioningResult grant(String realmId, String requesterId, JitAccessPackage accessPackage) {
        Objects.requireNonNull(accessPackage, "accessPackage must not be null");
        if (!realm.getId().equals(realmId) || !accessPackage.realmId().equals(realmId)) {
            return ProvisioningResult.failed(ProvisioningFailureCode.REALM_MISMATCH,
                    "The package does not belong to the current realm.");
        }
        try {
            UserModel user = session.users().getUserById(realm, requesterId);
            if (user == null) {
                return ProvisioningResult.failed(ProvisioningFailureCode.REQUESTER_MISSING,
                        "The requester no longer exists in the realm.");
            }
            GroupModel group = session.groups().getGroupById(realm, accessPackage.groupId());
            if (group == null) {
                return ProvisioningResult.failed(ProvisioningFailureCode.RESOURCE_MISSING,
                        "The package delivery group no longer exists.");
            }
            List<JitAccessPackage.RoleMapping> actualMappings = group.getRoleMappingsStream()
                    .map(KeycloakJitPackageMembership::mapping)
                    .toList();
            if (!accessPackage.groupId().equals(group.getId())
                    || !accessPackage.groupName().equals(group.getName())
                    || group.getParent() != null
                    || actualMappings.size() != accessPackage.roleMappings().size()
                    || !actualMappings.stream().collect(Collectors.toSet())
                            .equals(accessPackage.roleMappings().stream().collect(Collectors.toSet()))) {
                return ProvisioningResult.failed(ProvisioningFailureCode.RESOURCE_TYPE_MISMATCH,
                        "The package delivery group configuration has changed.");
            }
            if (user.isMemberOf(group)) {
                return ProvisioningResult.alreadyPresent();
            }
            user.joinGroup(group);
            return ProvisioningResult.granted();
        } catch (RuntimeException exception) {
            LOG.log(Level.SEVERE, "Unexpected package membership failure [realmId=" + realmId
                    + ", requesterId=" + requesterId + ", entitlementId=" + accessPackage.entitlementId() + "]",
                    exception);
            return ProvisioningResult.failed(ProvisioningFailureCode.UNEXPECTED_FAILURE,
                    "The package membership could not be provisioned.");
        }
    }

    private static JitAccessPackage.RoleMapping mapping(RoleModel role) {
        return new JitAccessPackage.RoleMapping(
                role.isClientRole() ? ResourceType.CLIENT_ROLE : ResourceType.REALM_ROLE, role.getId());
    }
}
