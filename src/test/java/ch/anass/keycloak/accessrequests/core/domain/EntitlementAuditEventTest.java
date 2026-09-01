package ch.anass.keycloak.accessrequests.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementAuditEventTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void creationEventKeepsAnImmutableSnapshotAndActor() {
        EntitlementAuditEvent event = EntitlementAuditEvent.created(entitlement(), "catalog-manager-1");

        assertEquals(EntitlementAuditEventType.ENTITLEMENT_CREATED, event.type());
        assertEquals("entitlement-1", event.entitlementId());
        assertEquals("realm-1", event.realmId());
        assertEquals("catalog-manager-1", event.actorId());
        assertEquals(CREATED_AT, event.occurredAt());
        assertEquals(ResourceType.REALM_ROLE, event.resourceType());
        assertEquals("role-1", event.resourceId());
        assertEquals("Finance Reader", event.displayName());
        assertEquals("Read-only access to finance data.", event.description());
        assertEquals(RiskLevel.HIGH, event.riskLevel());
        assertEquals("finance-approver", event.approverRoleId());
        assertFalse(event.requestable());
        assertEquals(0, event.version());
    }

    @Test
    void updateEventCapturesThePersistedPolicyState() {
        Entitlement updated = entitlement()
                .updateDetails(
                        "Finance Editor",
                        "Edit access to finance data.",
                        RiskLevel.CRITICAL,
                        "finance-owner",
                        CREATED_AT.plusSeconds(1))
                .publish(CREATED_AT.plusSeconds(1))
                .withVersion(3);

        EntitlementAuditEvent event = EntitlementAuditEvent.updated(updated, "catalog-manager-2");

        assertEquals(EntitlementAuditEventType.ENTITLEMENT_UPDATED, event.type());
        assertEquals("catalog-manager-2", event.actorId());
        assertEquals(CREATED_AT.plusSeconds(1), event.occurredAt());
        assertEquals("Finance Editor", event.displayName());
        assertEquals("Edit access to finance data.", event.description());
        assertEquals(RiskLevel.CRITICAL, event.riskLevel());
        assertEquals("finance-owner", event.approverRoleId());
        assertTrue(event.requestable());
        assertEquals(3, event.version());
    }

    private static Entitlement entitlement() {
        return Entitlement.create(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to finance data.",
                RiskLevel.HIGH,
                "finance-approver",
                CREATED_AT);
    }
}
