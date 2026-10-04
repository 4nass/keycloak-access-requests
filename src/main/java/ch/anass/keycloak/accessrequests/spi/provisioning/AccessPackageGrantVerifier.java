package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessPackageRepository;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Objects;
import java.util.Optional;

/** Resolves a grant only when its recorded package still matches the current Keycloak group. */
final class AccessPackageGrantVerifier {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final AccessPackageRepository packages;

    AccessPackageGrantVerifier(KeycloakSession session, RealmModel realm, AccessPackageRepository packages) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.packages = Objects.requireNonNull(packages, "packages must not be null");
    }

    Optional<Membership> resolve(AccessGrant grant) {
        Objects.requireNonNull(grant, "grant must not be null");
        if (grant.origin() != GrantOrigin.CREATED_BY_EXTENSION || grant.deliveryGroupId() == null
                || (grant.revocationState() != GrantRevocationState.UNVERIFIED
                && grant.revocationState() != GrantRevocationState.AUTHORIZED)
                || !realm.getId().equals(grant.realmId())) {
            return Optional.empty();
        }

        AccessPackage accessPackage = packages.findByEntitlementId(grant.realmId(), grant.entitlementId())
                .orElse(null);
        if (accessPackage == null || !grant.realmId().equals(accessPackage.realmId())
                || !grant.entitlementId().equals(accessPackage.entitlementId())
                || !grant.deliveryGroupId().equals(accessPackage.groupId())) {
            return Optional.empty();
        }

        GroupModel group = session.groups().getGroupById(realm, accessPackage.groupId());
        if (group == null || !AccessPackageGroupVerifier.matches(accessPackage, group)) {
            return Optional.empty();
        }
        UserModel user = session.users().getUserById(realm, grant.requesterId());
        return user == null ? Optional.empty() : Optional.of(new Membership(user, group));
    }

    record Membership(UserModel user, GroupModel group) {
    }
}
