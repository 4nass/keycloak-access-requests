package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.DecisionResponse;

import java.util.List;

/** JSON payloads for the administrative request history and audit feed. */
public final class AuditDto {

    private AuditDto() {
    }

    public record AdminRequestDetailResponse(
            String id, String requesterId, String entitlementId, ResourceType resourceType,
            String resourceName, DecisionStatus decisionStatus, ProvisioningStatus provisioningStatus,
            String createdAt, String provisioningClosedAt, String justification,
            DecisionResponse decision, List<AdminRequestHistoryEntryResponse> history,
            int historyPage, int historySize, long historyTotal) {
        public static AdminRequestDetailResponse from(AccessRequest request, List<AccessRequestEvent> events,
                int page, int size, long total) {
            DecisionResponse decision = request.approverId() == null ? null
                    : new DecisionResponse(request.approverId(), request.decisionComment(),
                            request.decidedAt().toString());
            return new AdminRequestDetailResponse(request.id(), request.requesterId(),
                    request.entitlementId(), request.resourceType(), request.resourceNameSnapshot(),
                    request.decisionStatus(), request.provisioningStatus(), request.createdAt().toString(),
                    request.provisioningClosedAt() == null ? null : request.provisioningClosedAt().toString(),
                    request.justification(), decision,
                    events.stream().map(AdminRequestHistoryEntryResponse::from).toList(), page, size, total);
        }
    }

    public record AdminRequestHistoryEntryResponse(
            String type, String actorId, String occurredAt,
            ProvisioningFailureCode failureCode, String closureReason) {
        public static AdminRequestHistoryEntryResponse from(AccessRequestEvent event) {
            ProvisioningFailureCode failureCode = event.type() == AccessRequestEventType.PROVISIONING_FAILED
                    ? ProvisioningFailureCode.fromStoredValue(event.metadata()) : null;
            String closureReason = event.type() == AccessRequestEventType.PROVISIONING_CLOSED
                    ? event.comment() : null;
            return new AdminRequestHistoryEntryResponse(event.type().name(), event.actorId(),
                    event.occurredAt().toString(), failureCode, closureReason);
        }
    }

    public record AuditEventListResponse(List<AuditEventResponse> items, int page, int size, long total) {
        public static AuditEventListResponse from(List<AccessRequestEvent> events, int page, int size, long total) {
            return new AuditEventListResponse(events.stream().map(AuditEventResponse::from).toList(),
                    page, size, total);
        }
    }

    public record AuditEventResponse(String id, String requestId, String type, String actorId,
            String occurredAt) {
        public static AuditEventResponse from(AccessRequestEvent event) {
            return new AuditEventResponse(event.id(), event.requestId(), event.type().name(),
                    event.actorId(), event.occurredAt().toString());
        }
    }
}
