package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalQueueEntry;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;

import java.util.List;
import java.util.function.Function;

/** JSON payloads for the approver queue and decisions. */
public final class ApprovalDto {

    private ApprovalDto() {
    }

    public record DecisionSubmission(String comment) {
    }

    public record CapabilitiesResponse(boolean canApprove) {
    }

    public record AssuranceErrorResponse(String code, String requestId, String requiredAcr) {
    }

    public enum AssuranceState {
        NOT_REQUIRED, SATISFIED, STEP_UP_REQUIRED, UNAVAILABLE
    }

    public record AssuranceStatus(AssuranceState state, String requiredAcr) {
    }

    public record PendingRequestListResponse(List<PendingRequestSummaryResponse> items,
            int page, int size, long total) {
        public static PendingRequestListResponse from(ApprovalQueuePage page,
                Function<String, String> requesterNameResolver,
                Function<RiskLevel, AssuranceStatus> assuranceResolver) {
            return new PendingRequestListResponse(
                    page.items().stream()
                            .map(entry -> PendingRequestSummaryResponse.from(entry, requesterNameResolver,
                                    assuranceResolver.apply(entry.riskLevel())))
                            .toList(),
                    page.page(), page.size(), page.total());
        }
    }

    public record PendingRequestSummaryResponse(
            String id, String requesterId, String requesterName, String entitlementId, ResourceType resourceType,
            String resourceName, RiskLevel riskLevel, String justification, String createdAt,
            Long durationSeconds, boolean permanent, AssuranceStatus assurance) {
        public static PendingRequestSummaryResponse from(ApprovalQueueEntry entry,
                Function<String, String> requesterNameResolver, AssuranceStatus assurance) {
            AccessRequest request = entry.request();
            return new PendingRequestSummaryResponse(request.id(), request.requesterId(),
                    requesterNameResolver.apply(request.requesterId()),
                    request.entitlementId(), request.resourceType(), request.resourceNameSnapshot(),
                    entry.riskLevel(), request.justification(), request.createdAt().toString(),
                    request.requestedDurationSeconds(), request.permanent(), assurance);
        }
    }
}
