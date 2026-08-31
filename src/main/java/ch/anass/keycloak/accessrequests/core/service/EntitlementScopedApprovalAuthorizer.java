package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.port.ApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.RoleMembershipReader;

import java.util.Objects;

/**
 * Authorizes decisions through the approver role configured for each entitlement.
 */
public final class EntitlementScopedApprovalAuthorizer implements ApprovalAuthorizer {

    private final EntitlementRepository entitlementRepository;
    private final RoleMembershipReader roleMembershipReader;

    public EntitlementScopedApprovalAuthorizer(
            EntitlementRepository entitlementRepository,
            RoleMembershipReader roleMembershipReader) {
        this.entitlementRepository = Objects.requireNonNull(entitlementRepository, "entitlementRepository must not be null");
        this.roleMembershipReader = Objects.requireNonNull(roleMembershipReader, "roleMembershipReader must not be null");
    }

    @Override
    public boolean canDecide(String realmId, String actorId, String entitlementId) {
        return entitlementRepository.findById(realmId, entitlementId)
                .map(entitlement -> roleMembershipReader.hasRole(realmId, actorId, entitlement.approverRoleId()))
                .orElse(false);
    }
}
