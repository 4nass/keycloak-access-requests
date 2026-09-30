package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.JitAccessPackageRepository;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.Objects;

/**
 * Resolves effective access for the authenticated user in the current realm.
 */
public final class KeycloakEffectiveAccessChecker implements EffectiveAccessChecker {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final UserModel user;
    private final JitAccessPackageRepository jitPackages;

    public KeycloakEffectiveAccessChecker(KeycloakSession session, RealmModel realm, UserModel user) {
        this(session, realm, user, new JitAccessPackageRepository() {
            @Override
            public void create(ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage accessPackage) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage>
                    findByEntitlementId(String realmId, String entitlementId) {
                return java.util.Optional.empty();
            }
        });
    }

    public KeycloakEffectiveAccessChecker(KeycloakSession session, RealmModel realm, UserModel user,
            JitAccessPackageRepository jitPackages) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.jitPackages = Objects.requireNonNull(jitPackages, "jitPackages must not be null");
    }

    @Override
    public boolean hasAccess(String realmId, String userId, Entitlement entitlement) {
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        if (!realm.getId().equals(realmId) || !user.getId().equals(userId)) {
            return false;
        }
        var accessPackage = jitPackages.findByEntitlementId(realmId, entitlement.id());
        if (accessPackage.isPresent()) {
            GroupModel group = session.groups().getGroupById(realm, accessPackage.get().groupId());
            return group != null && user.isMemberOf(group);
        }
        return switch (entitlement.resourceType()) {
            case REALM_ROLE -> hasRole(entitlement, false);
            case CLIENT_ROLE -> hasRole(entitlement, true);
            case GROUP -> isMemberOf(entitlement);
        };
    }

    private boolean hasRole(Entitlement entitlement, boolean clientRole) {
        RoleModel role = realm.getRoleById(entitlement.resourceId());
        return role != null && role.isClientRole() == clientRole && user.hasRole(role);
    }

    private boolean isMemberOf(Entitlement entitlement) {
        GroupModel group = session.groups().getGroupById(realm, entitlement.resourceId());
        return group != null && user.isMemberOf(group);
    }
}
