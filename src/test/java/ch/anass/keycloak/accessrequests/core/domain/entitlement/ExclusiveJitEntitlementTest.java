package ch.anass.keycloak.accessrequests.core.domain.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Declaring a resource dedicated to JIT access is an explicit, per-entitlement policy. */
class ExclusiveJitEntitlementTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void aNewEntitlementIsNotExclusiveJitEvenAfterPublication() {
        Entitlement entitlement = entitlement();

        assertFalse(entitlement.exclusiveJit());
        assertFalse(entitlement.publish(CREATED_AT.plusSeconds(1)).exclusiveJit());
    }

    @Test
    void theAdministratorCanOptInAndLaterOptOutWithoutChangingPublication() {
        Entitlement published = entitlement().publish(CREATED_AT.plusSeconds(1));

        Entitlement dedicated = published.withExclusiveJit(true, CREATED_AT.plusSeconds(2));
        Entitlement shared = dedicated.withExclusiveJit(false, CREATED_AT.plusSeconds(3));

        assertFalse(published.exclusiveJit());
        assertTrue(dedicated.exclusiveJit());
        assertTrue(dedicated.requestable());
        assertFalse(shared.exclusiveJit());
        assertTrue(shared.requestable());
    }

    @Test
    void policySurvivesMetadataPublicationAndVersionChanges() {
        Entitlement dedicated = entitlement().withExclusiveJit(true, CREATED_AT.plusSeconds(1));

        assertTrue(dedicated.publish(CREATED_AT.plusSeconds(2)).exclusiveJit());
        assertTrue(dedicated.publish(CREATED_AT.plusSeconds(2))
                .unpublish(CREATED_AT.plusSeconds(3)).exclusiveJit());
        assertTrue(dedicated.updateDetails("Updated", "Updated description", RiskLevel.HIGH,
                "approver-role", CREATED_AT.plusSeconds(4)).exclusiveJit());
        assertTrue(dedicated.withVersion(7).exclusiveJit());
    }

    private static Entitlement entitlement() {
        return Entitlement.create("entitlement-1", "realm-1", ResourceType.REALM_ROLE,
                "jit-role-1", "JIT role", "Dedicated temporary access", RiskLevel.LOW,
                "approver-role", CREATED_AT);
    }
}
