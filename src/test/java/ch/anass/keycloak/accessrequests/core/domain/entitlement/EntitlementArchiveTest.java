package ch.anass.keycloak.accessrequests.core.domain.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementArchiveTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void closingRequestsDoesNotArchiveTheEntitlement() {
        Entitlement closed = entitlement().publish(CREATED_AT.plusSeconds(1))
                .unpublish(CREATED_AT.plusSeconds(2));

        assertFalse(closed.requestable());
        assertFalse(closed.archived());
    }

    @Test
    void archivalClosesRequestsAndCannotBeReopened() {
        Entitlement archived = entitlement().publish(CREATED_AT.plusSeconds(1))
                .archive(CREATED_AT.plusSeconds(2));

        assertTrue(archived.archived());
        assertFalse(archived.requestable());
        assertSame(archived, archived.archive(CREATED_AT.plusSeconds(3)));
        assertThrows(IllegalStateException.class, () -> archived.publish(CREATED_AT.plusSeconds(3)));
        assertTrue(EntitlementAuditEvent.updated(archived, "admin-1").archived());
    }

    private static Entitlement entitlement() {
        return Entitlement.create("entitlement-1", "realm-1", ResourceType.GROUP,
                "package-group", "Package", "Temporary access", RiskLevel.LOW,
                "approver-role", CREATED_AT);
    }
}
