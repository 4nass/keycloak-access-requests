package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestHistoryReader;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestNotificationOutboxRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdminResponseMapperTest {

    @Test
    void mapsAnAuditPageWithoutPublishingEventDiagnostics() {
        AccessRequestEvent event = AccessRequestEvent.rehydrate("event-id", "request-id", "realm-id",
                AccessRequestEventType.PROVISIONING_FAILED, "actor-id", Instant.parse("2026-09-24T10:00:00Z"),
                "Internal JDBC password=secret", "RESOURCE_MISSING", 2L);

        var response = AdminResponseMapper.auditEvents(
                new JpaAccessRequestHistoryReader.AuditEventPage(List.of(event), 1, 20, 27));

        assertEquals(1, response.page());
        assertEquals(20, response.size());
        assertEquals(27, response.total());
        assertEquals("event-id", response.items().getFirst().id());
        assertEquals("request-id", response.items().getFirst().requestId());
        assertEquals("PROVISIONING_FAILED", response.items().getFirst().type());
        assertEquals("actor-id", response.items().getFirst().actorId());
    }

    @Test
    void mapsFailedProvisioningIncludingOptionalClosureFields() {
        Instant updatedAt = Instant.parse("2026-09-24T10:00:00Z");
        Instant closedAt = updatedAt.plusSeconds(60);
        var failure = new JpaAccessRequestRepository.FailedProvisioningRequest("request-id", "user-id",
                "entitlement-id", ResourceType.REALM_ROLE, "Role", DecisionStatus.APPROVED,
                ProvisioningStatus.FAILED, updatedAt, ProvisioningFailureCode.RESOURCE_MISSING,
                null, null, null);
        var closed = new JpaAccessRequestRepository.FailedProvisioningRequest("closed-id", "user-id",
                "entitlement-id", ResourceType.REALM_ROLE, "Role", DecisionStatus.APPROVED,
                ProvisioningStatus.FAILED, updatedAt, ProvisioningFailureCode.RESOURCE_MISSING,
                closedAt, "manager-id", "Resource was removed");

        var response = AdminResponseMapper.failedProvisioning(
                new JpaAccessRequestRepository.FailedProvisioningPage(List.of(failure, closed), 0, 20, 2));

        assertEquals(2, response.total());
        assertEquals(ProvisioningFailureCode.RESOURCE_MISSING, response.items().getFirst().failureCode());
        assertNull(response.items().getFirst().closedAt());
        assertEquals(closedAt.toString(), response.items().get(1).closedAt());
        assertEquals("manager-id", response.items().get(1).closedBy());
        assertEquals("Resource was removed", response.items().get(1).closureReason());
    }

    @Test
    void mapsNotificationSummaryWithoutChangingCounts() {
        var response = AdminResponseMapper.notificationSummary(
                new JpaAccessRequestNotificationOutboxRepository.NotificationOutboxSummary(1, 2, 3, 4, 5));

        assertEquals(1, response.pending());
        assertEquals(2, response.processing());
        assertEquals(3, response.delivered());
        assertEquals(4, response.discarded());
        assertEquals(5, response.failed());
    }
}
