package ch.anass.keycloak.accessrequests.core.domain.grant;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.DurationPolicy;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGrantTest {

    private static final Instant GRANTED_AT = Instant.parse("2026-09-01T10:15:30Z");

    @Test
    void activatesTemporaryAccessAtProvisioningSuccessAndExpiresAfterTheRequestedDuration() {
        AccessRequest request = successfulRequest(28_800L, false);

        AccessGrant grant = AccessGrant.from(request, entitlement(), GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT);

        assertEquals(GRANTED_AT, grant.recordedAt());
        assertEquals(GRANTED_AT.plus(Duration.ofHours(8)), grant.expiresAt());
        assertFalse(grant.canAutoRevoke(), "An expiration alone must not authorize revocation.");
    }

    @Test
    void leavesAnExplicitlyAllowedPermanentGrantWithoutAnExpiry() {
        Entitlement entitlement = Entitlement.create("entitlement-1", "realm-1", ResourceType.REALM_ROLE,
                "role-1", "Role", "Role description", RiskLevel.LOW, "approver-role",
                new DurationPolicy(Duration.ofDays(30), Duration.ofDays(90), true), GRANTED_AT);

        AccessGrant grant = AccessGrant.from(
                successfulRequest(null, true), entitlement, GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT);

        assertEquals(GRANTED_AT, grant.recordedAt());
        assertNull(grant.expiresAt());
    }

    @Test
    void doesNotScheduleExpiryForAccessThatExistedBeforeProvisioning() {
        AccessGrant grant = AccessGrant.from(
                successfulRequest(28_800L, false), entitlement(), GrantOrigin.PREEXISTING, GRANTED_AT);

        assertNull(grant.expiresAt());
        assertFalse(grant.canAutoRevoke());
    }

    @Test
    void rejectsAnExpiryThatCannotBeRepresentedAsAnInstant() {
        AccessRequest request = successfulRequest(Long.MAX_VALUE, false);

        assertThrows(IllegalArgumentException.class,
                () -> AccessGrant.from(request, entitlement(), GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT));

        AccessRequest beyondPersistedTimestamp = successfulRequest(Long.MAX_VALUE / 1_000 - 1, false);
        assertThrows(IllegalArgumentException.class,
                () -> AccessGrant.from(beyondPersistedTimestamp, entitlement(),
                        GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT));
    }

    @Test
    void rejectsAnExpiryAtOrBeforeActivationAndAnyExpiryOnPreexistingAccess() {
        AccessGrant grant = AccessGrant.from(
                successfulRequest(28_800L, false), entitlement(), GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT);

        assertThrows(IllegalArgumentException.class, () -> new AccessGrant(
                grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), grant.origin(), GRANTED_AT, GRANTED_AT,
                GrantRevocationState.UNVERIFIED, 0));
        assertThrows(IllegalArgumentException.class, () -> new AccessGrant(
                grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), GrantOrigin.PREEXISTING, GRANTED_AT, grant.expiresAt(),
                GrantRevocationState.UNVERIFIED, 0));
    }

    @Test
    void recordsOwnedAndPreexistingAccessSeparately() {
        AccessGrant owned = AccessGrant.from(request(), entitlement(), GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT);
        AccessGrant preexisting = AccessGrant.from(request(), entitlement(), GrantOrigin.PREEXISTING, GRANTED_AT);

        assertEquals("request-1", owned.requestId());
        assertEquals("realm-1", owned.realmId());
        assertEquals("user-1", owned.requesterId());
        assertEquals(ResourceType.REALM_ROLE, owned.resourceType());
        assertEquals("role-1", owned.resourceId());
        assertEquals(GRANTED_AT, owned.recordedAt());
        assertEquals(GrantRevocationState.UNVERIFIED, owned.revocationState());
        assertFalse(owned.canAutoRevoke(), "Historical origin must not authorize automatic revocation.");
        assertFalse(preexisting.canAutoRevoke());
    }

    @Test
    void invalidatesARecordedGrantAndRejectsRevocationWithoutExplicitAuthority() {
        AccessGrant owned = AccessGrant.from(
                successfulRequest(28_800L, false), entitlement(), GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT);

        assertThrows(IllegalStateException.class, owned::markRevoked);
        AccessGrant invalidated = owned.invalidate();
        assertEquals(GrantRevocationState.INVALIDATED, invalidated.revocationState());
        assertEquals(owned.expiresAt(), invalidated.expiresAt());
        assertFalse(invalidated.canAutoRevoke());
        assertThrows(IllegalStateException.class, invalidated::markRevoked);
    }

    @Test
    void onlyAnExplicitlyAuthorizedActiveJitGrantCanBeMarkedRevoked() {
        AccessGrant grant = AccessGrant.from(successfulRequest(28_800L, false), entitlement(),
                GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT);
        AccessGrant authorized = new AccessGrant(grant.requestId(), grant.realmId(), grant.requesterId(),
                grant.entitlementId(), grant.resourceType(), grant.resourceId(), grant.origin(), grant.recordedAt(),
                grant.expiresAt(), GrantRevocationState.AUTHORIZED, grant.version());

        assertTrue(authorized.canAutoRevoke());
        assertEquals(GrantRevocationState.REVOKED, authorized.markRevoked().revocationState());
        assertFalse(authorized.invalidate().canAutoRevoke());
        assertThrows(IllegalArgumentException.class, () -> new AccessGrant(
                grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), GrantOrigin.PREEXISTING, grant.recordedAt(),
                GrantRevocationState.AUTHORIZED, grant.version()));
    }

    @Test
    void rejectsAChangedOrCrossRealmEntitlement() {
        Entitlement changed = Entitlement.create("entitlement-1", "realm-1", ResourceType.REALM_ROLE,
                "other-role", "Other role", "Other role description", RiskLevel.LOW, "approver-role", GRANTED_AT);
        Entitlement crossRealm = Entitlement.create("entitlement-1", "other-realm", ResourceType.REALM_ROLE,
                "role-1", "Same role", "Same role description", RiskLevel.LOW, "approver-role", GRANTED_AT);

        assertThrows(IllegalArgumentException.class,
                () -> AccessGrant.from(request(), changed, GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> AccessGrant.from(request(), crossRealm, GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT));
    }

    @Test
    void rejectsARequestThatHasNotBeenSuccessfullyProvisioned() {
        AccessRequest pending = AccessRequest.create("request-2", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "role-1", "Role", "Business justification", GRANTED_AT);

        assertThrows(IllegalArgumentException.class,
                () -> AccessGrant.from(pending, entitlement(), GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT));
    }

    @Test
    void rejectsAnApprovedRequestWhoseProvisioningFailedOrHasNotStarted() {
        AccessRequest notStarted = AccessRequest.create("request-2", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "role-1", "Role", "Business justification",
                GRANTED_AT.minus(Duration.ofHours(1)), 28_800L, false);
        notStarted.approve("approver-1", "Approved", GRANTED_AT.minus(Duration.ofMinutes(5)));
        AccessRequest failed = notStarted.copy();
        failed.markProvisioningFailed(GRANTED_AT);

        assertThrows(IllegalArgumentException.class,
                () -> AccessGrant.from(notStarted, entitlement(), GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> AccessGrant.from(failed, entitlement(), GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT));
    }

    @Test
    void requiresAnOriginForSuccessAndForbidsOneOnFailure() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProvisioningResult(ProvisioningStatus.SUCCEEDED, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProvisioningResult(ProvisioningStatus.FAILED, "Failed",
                        ProvisioningFailureCode.UNKNOWN, GrantOrigin.CREATED_BY_EXTENSION));
        assertEquals(GrantOrigin.CREATED_BY_EXTENSION, ProvisioningResult.granted().grantOrigin());
        assertEquals(GrantOrigin.PREEXISTING, ProvisioningResult.alreadyPresent().grantOrigin());
        assertEquals(null, ProvisioningResult.failed("Failed").grantOrigin());
    }

    private static Entitlement entitlement() {
        return Entitlement.create("entitlement-1", "realm-1", ResourceType.REALM_ROLE,
                "role-1", "Role", "Role description", RiskLevel.LOW, "approver-role", GRANTED_AT);
    }

    private static AccessRequest request() {
        AccessRequest request = AccessRequest.create("request-1", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "role-1", "Role", "Business justification", GRANTED_AT);
        request.approve("approver-1", "Approved", GRANTED_AT);
        request.markProvisioningSucceeded(GRANTED_AT);
        return request;
    }

    private static AccessRequest successfulRequest(Long durationSeconds, boolean permanent) {
        AccessRequest request = AccessRequest.create("request-1", "realm-1", "user-1", "entitlement-1",
                ResourceType.REALM_ROLE, "role-1", "Role", "Business justification",
                GRANTED_AT.minus(Duration.ofHours(1)), durationSeconds, permanent);
        request.approve("approver-1", "Approved", GRANTED_AT.minus(Duration.ofMinutes(5)));
        request.markProvisioningSucceeded(GRANTED_AT);
        return request;
    }
}
