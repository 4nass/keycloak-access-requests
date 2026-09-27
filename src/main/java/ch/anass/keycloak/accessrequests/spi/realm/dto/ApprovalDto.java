package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalQueueEntry;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;

import java.util.List;

/** JSON payloads for the approver queue and decisions. */
public final class ApprovalDto {

    private ApprovalDto() {
    }

    public record DecisionSubmission(String comment) {
    }

    public record CapabilitiesResponse(boolean canApprove) {
    }

    public record PendingRequestListResponse(List<PendingRequestSummaryResponse> items,
            int page, int size, long total) {
        public static PendingRequestListResponse from(ApprovalQueuePage page) {
            return new PendingRequestListResponse(
                    page.items().stream().map(PendingRequestSummaryResponse::from).toList(),
                    page.page(), page.size(), page.total());
        }
    }

    public record PendingRequestSummaryResponse(
            String id, String requesterId, String entitlementId, ResourceType resourceType,
            String resourceName, RiskLevel riskLevel, String justification, String createdAt,
            Long durationSeconds, boolean permanent) {
        public static PendingRequestSummaryResponse from(ApprovalQueueEntry entry) {
            AccessRequest request = entry.request();
            return new PendingRequestSummaryResponse(request.id(), request.requesterId(),
                    request.entitlementId(), request.resourceType(), request.resourceNameSnapshot(),
                    entry.riskLevel(), request.justification(), request.createdAt().toString(),
                    request.requestedDurationSeconds(), request.permanent());
        }
    }
}
