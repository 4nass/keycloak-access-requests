package ch.anass.keycloak.accessrequests.core.domain.grant;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The authority flag represents a current, independent binding check, never the AR_PKG_ name alone. */
class AccessPackageMembershipRevocationLifecycleTest {

    private static final Instant ACTIVATED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant EXPIRES_AT = ACTIVATED_AT.plus(Duration.ofHours(4));
    private static final String GROUP_ID = "package-group-1";

    @Test
    void anOwnedTemporaryMembershipBecomesRevocableOnlyAfterIndependentAuthorization() {
        AccessGrant unverified = membership(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.UNVERIFIED);

        assertFalse(unverified.canAutoRevokeAt(EXPIRES_AT));
        assertThrows(IllegalStateException.class, unverified::markRevoked);
        AccessGrant authorized = unverified.authorizeForRevocation(entitlement(), true);

        assertEquals(GrantRevocationState.AUTHORIZED, authorized.revocationState());
        assertEquals(GROUP_ID, authorized.deliveryGroupId());
        assertSame(authorized, authorized.authorizeForRevocation(entitlement(), true));
        assertFalse(authorized.canAutoRevokeAt(EXPIRES_AT.minusNanos(1)));
        assertTrue(authorized.canAutoRevokeAt(EXPIRES_AT));
        assertEquals(GrantRevocationState.REVOKED, authorized.markRevoked().revocationState());
        assertFalse(authorized.markRevoked().canAutoRevokeAt(EXPIRES_AT.plusSeconds(1)));
    }

    @Test
    void aPackageWithoutVerifiedExclusiveManagementCannotBeAuthorized() {
        AccessGrant grant = membership(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.UNVERIFIED);

        assertThrows(IllegalStateException.class, () -> grant.authorizeForRevocation(entitlement(), false));
        assertFalse(grant.canAutoRevokeAt(EXPIRES_AT));
    }

    @Test
    void preexistingPermanentAndInvalidatedMembershipsCannotBeRemovedAtExpiry() {
        AccessGrant preexisting = membership(GrantOrigin.PREEXISTING, null, GrantRevocationState.UNVERIFIED);
        AccessGrant permanent = membership(GrantOrigin.CREATED_BY_EXTENSION, null, GrantRevocationState.UNVERIFIED);
        AccessGrant invalidated = membership(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.INVALIDATED);

        for (AccessGrant grant : List.of(preexisting, permanent, invalidated)) {
            assertFalse(grant.canAutoRevokeAt(EXPIRES_AT.plus(Duration.ofDays(30))));
            assertThrows(IllegalStateException.class, () -> grant.authorizeForRevocation(entitlement(), true));
            assertThrows(IllegalStateException.class, grant::markRevoked);
        }
    }

    @Test
    void theRecordedDeliveryGroupMustBeTheProvisionedGroupResource() {
        assertThrows(IllegalArgumentException.class, () -> new AccessGrant("request-1", "realm-1", "user-1",
                "entitlement-1", ResourceType.GROUP, GROUP_ID, GrantOrigin.CREATED_BY_EXTENSION,
                ACTIVATED_AT, EXPIRES_AT, GrantRevocationState.UNVERIFIED, 0, "other-group"));
        assertThrows(IllegalArgumentException.class, () -> new AccessGrant("request-1", "realm-1", "user-1",
                "entitlement-1", ResourceType.REALM_ROLE, "role-1", GrantOrigin.CREATED_BY_EXTENSION,
                ACTIVATED_AT, EXPIRES_AT, GrantRevocationState.UNVERIFIED, 0, GROUP_ID));
    }

    @Test
    void aPackageBindingForAnotherGroupOrRealmCannotBeRecordedAsTheDeliveryTarget() {
        AccessRequest request = AccessRequest.create("request-1", "realm-1", "user-1", "entitlement-1",
                ResourceType.GROUP, GROUP_ID, "Package", "Business need", ACTIVATED_AT.minusSeconds(3600),
                Duration.ofHours(4).toSeconds(), false);
        request.approve("approver-1", "Approved", ACTIVATED_AT.minusSeconds(300));
        request.markProvisioningSucceeded(ACTIVATED_AT);

        AccessPackage wrongGroup = packageBinding("realm-1", "other-group");
        AccessPackage wrongRealm = packageBinding("other-realm", GROUP_ID);

        assertThrows(IllegalArgumentException.class, () -> AccessGrant.from(request, entitlement(),
                GrantOrigin.CREATED_BY_EXTENSION, ACTIVATED_AT, wrongGroup));
        assertThrows(IllegalArgumentException.class, () -> AccessGrant.from(request, entitlement(),
                GrantOrigin.CREATED_BY_EXTENSION, ACTIVATED_AT, wrongRealm));
        assertEquals(GROUP_ID, AccessGrant.from(request, entitlement(), GrantOrigin.CREATED_BY_EXTENSION,
                ACTIVATED_AT, packageBinding("realm-1", GROUP_ID)).deliveryGroupId());
    }

    private static AccessGrant membership(GrantOrigin origin, Instant expiresAt, GrantRevocationState state) {
        return new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1", ResourceType.GROUP,
                GROUP_ID, origin, ACTIVATED_AT, expiresAt, state, 0, GROUP_ID);
    }

    private static Entitlement entitlement() {
        return Entitlement.create("entitlement-1", "realm-1", ResourceType.GROUP, GROUP_ID,
                "Package", "Dedicated delivery group", RiskLevel.LOW, "approver-role", ACTIVATED_AT);
    }

    private static AccessPackage packageBinding(String realmId, String groupId) {
        return new AccessPackage("entitlement-1", realmId, groupId, "AR_PKG_PACKAGE",
                List.of(new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "source-role")));
    }
}
