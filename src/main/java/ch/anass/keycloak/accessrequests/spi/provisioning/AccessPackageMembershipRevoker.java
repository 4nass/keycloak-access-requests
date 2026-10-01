package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevoker;
import ch.anass.keycloak.accessrequests.core.port.AccessPackageRepository;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.RoleUtils;

import java.util.Objects;

/** Removes only the direct membership in the package group, never its source role mappings. */
public final class AccessPackageMembershipRevoker implements AccessGrantRevoker {

    private final AccessPackageGrantVerifier verifier;

    public AccessPackageMembershipRevoker(KeycloakSession session, RealmModel realm,
            AccessPackageRepository packages) {
        verifier = new AccessPackageGrantVerifier(session, realm, packages);
    }

    @Override
    public void revoke(AccessGrant grant) {
        Objects.requireNonNull(grant, "grant must not be null");
        if (grant.revocationState() != GrantRevocationState.AUTHORIZED) {
            throw new IllegalStateException("Package grant is not authorized for revocation");
        }
        AccessPackageGrantVerifier.Membership membership = verifier.resolve(grant)
                .orElseThrow(() -> new IllegalStateException("Package grant ownership is no longer verifiable"));
        if (RoleUtils.isDirectMember(membership.user().getGroupsStream(), membership.group())) {
            membership.user().leaveGroup(membership.group());
        }
    }
}
