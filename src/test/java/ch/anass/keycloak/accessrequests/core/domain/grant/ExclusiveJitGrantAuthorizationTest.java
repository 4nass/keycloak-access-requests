package ch.anass.keycloak.accessrequests.core.domain.grant;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provenance, administrator policy and independent authority are all required to enable expiry. */
class ExclusiveJitGrantAuthorizationTest {

    private static final Instant GRANTED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant EXPIRES_AT = GRANTED_AT.plusSeconds(3600);

    @Test
    void authorizesOnlyATemporaryMappingCreatedByTheExtensionForAnExclusiveJitEntitlement() {
        AccessGrant authorized = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.UNVERIFIED).authorizeForRevocation(entitlement(true), true);

        assertEquals(GrantRevocationState.AUTHORIZED, authorized.revocationState());
        assertTrue(authorized.canAutoRevoke());
        assertEquals(EXPIRES_AT, authorized.expiresAt());
    }

    @Test
    void publicationAndCreationProvenanceAloneNeverConferRevocationAuthority() {
        AccessGrant grant = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.UNVERIFIED);

        assertThrows(IllegalStateException.class,
                () -> grant.authorizeForRevocation(entitlement(false).publish(GRANTED_AT), true));
        assertThrows(IllegalStateException.class,
                () -> grant.authorizeForRevocation(entitlement(true), false));
        assertFalse(grant.canAutoRevoke());
    }

    @Test
    void neverClaimsPreexistingOrPermanentAccessEvenIfDeclaredExclusive() {
        AccessGrant preexisting = grant(GrantOrigin.PREEXISTING, null, GrantRevocationState.UNVERIFIED);
        AccessGrant permanent = grant(GrantOrigin.CREATED_BY_EXTENSION, null, GrantRevocationState.UNVERIFIED);

        assertThrows(IllegalStateException.class,
                () -> preexisting.authorizeForRevocation(entitlement(true), true));
        assertThrows(IllegalStateException.class,
                () -> permanent.authorizeForRevocation(entitlement(true), true));
    }

    @Test
    void rejectsCrossRealmOrChangedResourcePolicies() {
        AccessGrant grant = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.UNVERIFIED);
        Entitlement otherRealm = Entitlement.create("entitlement-1", "other-realm", ResourceType.REALM_ROLE,
                "jit-role-1", "JIT role", "Dedicated", RiskLevel.LOW, "approver-role", GRANTED_AT)
                .withExclusiveJit(true, GRANTED_AT);
        Entitlement otherResource = Entitlement.create("entitlement-1", "realm-1", ResourceType.REALM_ROLE,
                "shared-role-1", "Shared role", "Shared", RiskLevel.LOW, "approver-role", GRANTED_AT)
                .withExclusiveJit(true, GRANTED_AT);

        assertThrows(IllegalArgumentException.class,
                () -> grant.authorizeForRevocation(otherRealm, true));
        assertThrows(IllegalArgumentException.class,
                () -> grant.authorizeForRevocation(otherResource, true));
    }

    @Test
    void invalidatedOrRevokedGrantsCannotBeReauthorized() {
        AccessGrant grant = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.UNVERIFIED);
        AccessGrant authorized = grant.authorizeForRevocation(entitlement(true), true);

        assertThrows(IllegalStateException.class,
                () -> grant.invalidate().authorizeForRevocation(entitlement(true), true));
        assertThrows(IllegalStateException.class,
                () -> authorized.markRevoked().authorizeForRevocation(entitlement(true), true));
    }

    @Test
    void repeatAuthorizationDoesNotResetStateOrVersion() {
        AccessGrant authorized = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.UNVERIFIED).authorizeForRevocation(entitlement(true), true);

        assertEquals(authorized, authorized.authorizeForRevocation(entitlement(true), true));
    }

    private static Entitlement entitlement(boolean exclusiveJit) {
        return Entitlement.create("entitlement-1", "realm-1", ResourceType.REALM_ROLE,
                "jit-role-1", "JIT role", "Dedicated", RiskLevel.LOW, "approver-role", GRANTED_AT)
                .withExclusiveJit(exclusiveJit, GRANTED_AT);
    }

    private static AccessGrant grant(GrantOrigin origin, Instant expiresAt, GrantRevocationState state) {
        return new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1", ResourceType.REALM_ROLE,
                "jit-role-1", origin, GRANTED_AT, expiresAt, state, 0);
    }
}
