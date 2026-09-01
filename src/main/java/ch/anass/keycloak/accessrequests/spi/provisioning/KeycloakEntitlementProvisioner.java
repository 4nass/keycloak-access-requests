package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningResult;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.port.EntitlementProvisioner;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.Objects;

/**
 * Assigns Keycloak realm roles, client roles, and groups to a user in the current realm.
 */
public final class KeycloakEntitlementProvisioner implements EntitlementProvisioner {

    private final KeycloakSession session;
    private final RealmModel realm;

    public KeycloakEntitlementProvisioner(KeycloakSession session, RealmModel realm) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
    }

    @Override
    public boolean supports(ResourceType resourceType) {
        return resourceType != null;
    }

    @Override
    public ProvisioningResult grant(String realmId, String requesterId, Entitlement entitlement) {
        if (realmId == null || requesterId == null || entitlement == null
                || !realm.getId().equals(realmId)
                || !realmId.equals(entitlement.realmId())) {
            return ProvisioningResult.failed("The entitlement does not belong to the current realm.");
        }
        try {
            UserModel requester = session.users().getUserById(realm, requesterId);
            if (requester == null) {
                return ProvisioningResult.failed("The requester no longer exists in the realm.");
            }
            return switch (entitlement.resourceType()) {
                case REALM_ROLE -> grantRealmRole(requester, entitlement.resourceId());
                case CLIENT_ROLE -> grantClientRole(requester, entitlement.resourceId());
                case GROUP -> joinGroup(requester, entitlement.resourceId());
            };
        } catch (RuntimeException exception) {
            return ProvisioningResult.failed(
                    "Keycloak could not provision the entitlement: " + exception.getClass().getSimpleName());
        }
    }

    private ProvisioningResult grantRealmRole(UserModel requester, String roleId) {
        RoleModel role = realm.getRoleById(roleId);
        if (role == null) {
            return ProvisioningResult.failed("The configured Keycloak role no longer exists.");
        }
        if (role.isClientRole()) {
            return ProvisioningResult.failed("The configured entitlement is not a realm role.");
        }
        return grantRole(requester, role);
    }

    private ProvisioningResult grantClientRole(UserModel requester, String roleId) {
        RoleModel role = realm.getRoleById(roleId);
        if (role == null) {
            return ProvisioningResult.failed("The configured Keycloak role no longer exists.");
        }
        if (!role.isClientRole()) {
            return ProvisioningResult.failed("The configured entitlement is not a client role.");
        }
        return grantRole(requester, role);
    }

    private ProvisioningResult grantRole(UserModel requester, RoleModel role) {
        if (!requester.hasRole(role)) {
            requester.grantRole(role);
        }
        return ProvisioningResult.succeeded();
    }

    private ProvisioningResult joinGroup(UserModel requester, String groupId) {
        GroupModel group = session.groups().getGroupById(realm, groupId);
        if (group == null) {
            return ProvisioningResult.failed("The configured Keycloak group no longer exists.");
        }
        if (!requester.isMemberOf(group)) {
            requester.joinGroup(group);
        }
        return ProvisioningResult.succeeded();
    }
}
