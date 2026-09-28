package ch.anass.keycloak.accessrequests.core.domain.grant;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGrantRevocationLifecycleTest {

    private static final Instant ACTIVATED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant EXPIRES_AT = ACTIVATED_AT.plus(Duration.ofHours(4));

    @Test
    void anOwnedTemporaryGrantBecomesDueAtItsExactExpiryButNotBefore() {
        AccessGrant grant = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT, GrantRevocationState.AUTHORIZED);

        assertFalse(grant.isDueAt(EXPIRES_AT.minusNanos(1)));
        assertTrue(grant.isDueAt(EXPIRES_AT));
        assertTrue(grant.isDueAt(EXPIRES_AT.plusSeconds(1)));
    }

    @Test
    void dueTimeAloneNeverAuthorizesRemoval() {
        AccessGrant unverified = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.UNVERIFIED);
        AccessGrant invalidated = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.INVALIDATED);
        AccessGrant revoked = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.REVOKED);

        assertFalse(unverified.canAutoRevokeAt(EXPIRES_AT));
        assertFalse(invalidated.canAutoRevokeAt(EXPIRES_AT));
        assertFalse(revoked.canAutoRevokeAt(EXPIRES_AT));
    }

    @Test
    void permanentAndPreexistingAccessNeverBecomesDueForAutomaticRevocation() {
        AccessGrant permanent = grant(GrantOrigin.CREATED_BY_EXTENSION, null, GrantRevocationState.AUTHORIZED);
        AccessGrant preexisting = grant(GrantOrigin.PREEXISTING, null, GrantRevocationState.UNVERIFIED);

        assertFalse(permanent.isDueAt(EXPIRES_AT.plus(Duration.ofDays(365))));
        assertFalse(preexisting.isDueAt(EXPIRES_AT.plus(Duration.ofDays(365))));
        assertFalse(permanent.canAutoRevokeAt(EXPIRES_AT.plus(Duration.ofDays(365))));
        assertFalse(preexisting.canAutoRevokeAt(EXPIRES_AT.plus(Duration.ofDays(365))));
    }

    @Test
    void invalidationMakesPreviouslyAuthorizedAccessPermanentlyIneligible() {
        AccessGrant authorized = grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.AUTHORIZED);

        assertFalse(authorized.canAutoRevokeAt(EXPIRES_AT.minusNanos(1)));
        assertTrue(authorized.canAutoRevokeAt(EXPIRES_AT));
        AccessGrant invalidated = authorized.invalidate();
        assertFalse(invalidated.canAutoRevokeAt(EXPIRES_AT.plusSeconds(1)));
        assertThrows(IllegalStateException.class, invalidated::markRevoked);
    }

    private static AccessGrant grant(GrantOrigin origin, Instant expiresAt, GrantRevocationState state) {
        return new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1", ResourceType.REALM_ROLE,
                "jit-role-1", origin, ACTIVATED_AT, expiresAt, state, 0);
    }
}
