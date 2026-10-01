package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessPackageRepository;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakAccessPackageGrantLifecycleTest {

    @Test
    void authorizesOnlyThePersistedRootPackageWithItsOriginalRoleMappings() {
        Fixture fixture = new Fixture();
        fixture.effectiveSourceRoleElsewhere = true;

        assertTrue(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));
        assertEquals(0, fixture.groupLeaves);
        assertEquals(0, fixture.directRoleRemovals);
    }

    @Test
    void aMatchingNameAloneNeverEstablishesOwnership() {
        Fixture fixture = new Fixture();
        fixture.bindingPresent = false;
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));

        fixture.bindingPresent = true;
        fixture.bindingGroupId = "other-group";
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));

        fixture.bindingGroupId = "jit-group-1";
        fixture.bindingRealmId = "other-realm";
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));
    }

    @Test
    void rejectsAReplacedMovedRenamedOrRemappedDeliveryGroup() {
        Fixture fixture = new Fixture();
        fixture.groupId = "replacement-id";
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));

        fixture.groupId = "jit-group-1";
        fixture.groupName = "business-group";
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));

        fixture.groupName = "AR_PKG_REPORTING";
        fixture.nestedGroup = true;
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));

        fixture.nestedGroup = false;
        fixture.groupRoles.removeLast();
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));

        fixture.groupRoles.add(fixture.clientRole);
        fixture.groupRoles.add(fixture.extraRole);
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.UNVERIFIED)));
    }

    @Test
    void failsClosedForWrongRealmMissingUserOrMissingGroup() {
        Fixture fixture = new Fixture();
        AccessGrant anotherRealm = new AccessGrant("request-1", "other-realm", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "realm-role-1", GrantOrigin.CREATED_BY_EXTENSION,
                Fixture.GRANTED_AT, Fixture.GRANTED_AT.plus(Duration.ofHours(4)),
                GrantRevocationState.AUTHORIZED, 0, "jit-group-1");
        assertFalse(fixture.lifecycle().isExclusivelyManaged(anotherRealm));

        fixture.userExists = false;
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.AUTHORIZED)));

        fixture.userExists = true;
        fixture.groupExists = false;
        assertFalse(fixture.lifecycle().isExclusivelyManaged(fixture.grant(GrantRevocationState.AUTHORIZED)));
    }

    @Test
    void doesNotClaimPreexistingPermanentOrUnboundAccess() {
        Fixture fixture = new Fixture();
        AccessGrant unbound = new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "realm-role-1", GrantOrigin.CREATED_BY_EXTENSION,
                Fixture.GRANTED_AT, Fixture.GRANTED_AT.plus(Duration.ofHours(4)),
                GrantRevocationState.UNVERIFIED, 0);
        AccessGrant preexisting = new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "realm-role-1", GrantOrigin.PREEXISTING,
                Fixture.GRANTED_AT, null, GrantRevocationState.UNVERIFIED, 0, "jit-group-1");
        AccessGrant permanent = new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "realm-role-1", GrantOrigin.CREATED_BY_EXTENSION,
                Fixture.GRANTED_AT, null, GrantRevocationState.UNVERIFIED, 0, "jit-group-1");

        assertFalse(fixture.lifecycle().isExclusivelyManaged(unbound));
        assertFalse(fixture.lifecycle().isExclusivelyManaged(preexisting));
        assertFalse(fixture.lifecycle().isExclusivelyManaged(permanent));
    }

    @Test
    void revokesOnlyPackageMembershipEvenWhenTheSourceRoleIsEffectiveElsewhere() {
        Fixture fixture = new Fixture();
        fixture.effectiveSourceRoleElsewhere = true;
        AccessGrant grant = fixture.grant(GrantRevocationState.AUTHORIZED);

        fixture.lifecycle().revoke(grant);

        assertEquals(1, fixture.groupLeaves);
        assertEquals("jit-group-1", fixture.lastLeftGroupId);
        assertEquals(0, fixture.directRoleRemovals);
        assertEquals(0, fixture.otherGroupLeaves);
        assertTrue(fixture.effectiveSourceRoleElsewhere);
    }

    @Test
    void alreadyAbsentMembershipCanBeRevokedIdempotently() {
        Fixture fixture = new Fixture();
        AccessGrant grant = fixture.grant(GrantRevocationState.AUTHORIZED);

        fixture.lifecycle().revoke(grant);
        fixture.lifecycle().revoke(grant);

        assertEquals(1, fixture.groupLeaves);
        assertEquals(0, fixture.directRoleRemovals);
    }

    @Test
    void absentMembershipDoesNotInvalidateAnOtherwiseOwnedPackage() {
        Fixture fixture = new Fixture();
        fixture.member = false;
        AccessGrant grant = fixture.grant(GrantRevocationState.AUTHORIZED);

        assertTrue(fixture.lifecycle().isExclusivelyManaged(grant));
        fixture.lifecycle().revoke(grant);
        assertEquals(0, fixture.groupLeaves);
        assertEquals(0, fixture.directRoleRemovals);
    }

    @Test
    void revokerRefusesAnUnverifiedOrCrossRealmGrant() {
        Fixture fixture = new Fixture();
        AccessGrant unverified = fixture.grant(GrantRevocationState.UNVERIFIED);
        AccessGrant anotherRealm = new AccessGrant("request-1", "other-realm", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "realm-role-1", GrantOrigin.CREATED_BY_EXTENSION,
                Fixture.GRANTED_AT, Fixture.GRANTED_AT.plus(Duration.ofHours(4)),
                GrantRevocationState.AUTHORIZED, 0, "jit-group-1");

        assertThrows(IllegalStateException.class, () -> fixture.lifecycle().revoke(unverified));
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle().revoke(anotherRealm));
        assertEquals(0, fixture.groupLeaves);
        assertEquals(0, fixture.directRoleRemovals);
    }

    @Test
    void revocationRechecksBindingAndGroupInsteadOfTrustingAnEarlierAuthorization() {
        Fixture fixture = new Fixture();
        AccessGrant grant = fixture.grant(GrantRevocationState.AUTHORIZED);
        assertTrue(fixture.lifecycle().isExclusivelyManaged(grant));

        fixture.groupName = "renamed-after-authorization";
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle().revoke(grant));
        assertEquals(0, fixture.groupLeaves);

        fixture.groupName = "AR_PKG_REPORTING";
        fixture.bindingGroupId = "other-group";
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle().revoke(grant));
        assertEquals(0, fixture.groupLeaves);
    }

    @Test
    void revocationFailsClosedForMissingUserOrGroupAndDoesNotSwallowKeycloakFailures() {
        Fixture fixture = new Fixture();
        AccessGrant grant = fixture.grant(GrantRevocationState.AUTHORIZED);

        fixture.userExists = false;
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle().revoke(grant));
        fixture.userExists = true;
        fixture.groupExists = false;
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle().revoke(grant));
        fixture.groupExists = true;
        fixture.leaveFails = true;
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle().revoke(grant));
        assertEquals(0, fixture.groupLeaves);
        assertEquals(0, fixture.directRoleRemovals);
    }

    private static final class Fixture {
        private static final Instant GRANTED_AT = Instant.parse("2026-09-01T10:00:00Z");

        private boolean bindingPresent = true;
        private String bindingRealmId = "realm-1";
        private String bindingGroupId = "jit-group-1";
        private boolean userExists = true;
        private boolean groupExists = true;
        private boolean member = true;
        private boolean nestedGroup;
        private boolean leaveFails;
        private boolean effectiveSourceRoleElsewhere;
        private String groupId = "jit-group-1";
        private String groupName = "AR_PKG_REPORTING";
        private String lastLeftGroupId;
        private int groupLeaves;
        private int otherGroupLeaves;
        private int directRoleRemovals;
        private final RoleModel realmRole = role("realm-role-1", false);
        private final RoleModel clientRole = role("client-role-1", true);
        private final RoleModel extraRole = role("extra-role", false);
        private final List<RoleModel> groupRoles = new ArrayList<>(List.of(realmRole, clientRole));

        AccessGrant grant(GrantRevocationState state) {
            return new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1", ResourceType.REALM_ROLE,
                    "realm-role-1", GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT,
                    GRANTED_AT.plus(Duration.ofHours(4)), state, 0, "jit-group-1");
        }

        KeycloakAccessPackageGrantLifecycle lifecycle() {
            RealmModel realm = proxy(RealmModel.class, (self, method, args) ->
                    method.getName().equals("getId") ? "realm-1" : null);
            GroupModel group = proxy(GroupModel.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> groupId;
                case "getName" -> groupName;
                case "getParent" -> nestedGroup ? proxy(GroupModel.class, (parent, call, values) -> null) : null;
                case "getRoleMappingsStream" -> groupRoles.stream();
                default -> null;
            });
            UserModel user = proxy(UserModel.class, (self, method, args) -> switch (method.getName()) {
                case "isMemberOf" -> member;
                case "leaveGroup" -> {
                    if (leaveFails) {
                        throw new IllegalStateException("Keycloak group removal failed");
                    }
                    String leftGroupId = ((GroupModel) args[0]).getId();
                    if ("jit-group-1".equals(leftGroupId)) {
                        groupLeaves++;
                        lastLeftGroupId = leftGroupId;
                        member = false;
                    } else {
                        otherGroupLeaves++;
                    }
                    yield null;
                }
                case "deleteRoleMapping" -> {
                    directRoleRemovals++;
                    yield null;
                }
                case "hasRole" -> effectiveSourceRoleElsewhere;
                default -> null;
            });
            UserProvider users = proxy(UserProvider.class, (self, method, args) ->
                    method.getName().equals("getUserById") && userExists ? user : null);
            GroupProvider groups = proxy(GroupProvider.class, (self, method, args) ->
                    method.getName().equals("getGroupById") && groupExists ? group : null);
            KeycloakSession session = proxy(KeycloakSession.class, (self, method, args) -> switch (method.getName()) {
                case "users" -> users;
                case "groups" -> groups;
                default -> null;
            });
            AccessPackageRepository packages = new AccessPackageRepository() {
                @Override
                public void create(AccessPackage accessPackage) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<AccessPackage> findByEntitlementId(String realmId, String entitlementId) {
                    if (!bindingPresent || !"realm-1".equals(realmId) || !"entitlement-1".equals(entitlementId)) {
                        return Optional.empty();
                    }
                    return Optional.of(new AccessPackage("entitlement-1", bindingRealmId, bindingGroupId,
                            "AR_PKG_REPORTING", List.of(
                                    new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "realm-role-1"),
                                    new AccessPackage.RoleMapping(ResourceType.CLIENT_ROLE, "client-role-1"))));
                }
            };
            return new KeycloakAccessPackageGrantLifecycle(session, realm, packages);
        }

        private static RoleModel role(String id, boolean clientRole) {
            return proxy(RoleModel.class, (self, method, args) -> switch (method.getName()) {
                case "getId" -> id;
                case "isClientRole" -> clientRole;
                default -> null;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
