package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantMembershipInspector;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.RoleUtils;

import java.util.Objects;

/** Never mutates membership; inspects the exact group ID recorded at grant activation. */
public final class AccessPackageMembershipInspector implements AccessGrantMembershipInspector {

    private final KeycloakSession session;
    private final RealmModel realm;

    public AccessPackageMembershipInspector(KeycloakSession session, RealmModel realm) {
        this.session = Objects.requireNonNull(session);
        this.realm = Objects.requireNonNull(realm);
    }

    @Override
    public Membership membership(AccessGrant grant) {
        if (!realm.getId().equals(grant.realmId()) || grant.deliveryGroupId() == null) {
            return Membership.UNVERIFIABLE;
        }
        UserModel user = session.users().getUserById(realm, grant.requesterId());
        GroupModel group = session.groups().getGroupById(realm, grant.deliveryGroupId());
        if (user == null || group == null) {
            return Membership.ABSENT;
        }
        return RoleUtils.isDirectMember(user.getGroupsStream(), group)
                ? Membership.PRESENT : Membership.ABSENT;
    }
}
