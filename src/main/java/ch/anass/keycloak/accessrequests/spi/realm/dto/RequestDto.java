package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestDetails;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;

import java.util.List;
import java.util.function.Function;

/** JSON payloads for requester-owned access requests. */
public final class RequestDto {

    private RequestDto() {
    }

    public record RequestSubmission(String entitlementId, String justification,
            Long durationSeconds, Boolean permanent) {
        public RequestSubmission(String entitlementId, String justification) {
            this(entitlementId, justification, null, null);
        }
    }

    public record RequestResponse(
            String id, String entitlementId, DecisionStatus decisionStatus,
            ProvisioningStatus provisioningStatus, Long durationSeconds, boolean permanent) {
        public static RequestResponse from(AccessRequest request) {
            return new RequestResponse(request.id(), request.entitlementId(),
                    request.decisionStatus(), request.provisioningStatus(),
                    request.requestedDurationSeconds(), request.permanent());
        }
    }

    public record RequestListResponse(List<RequestSummaryResponse> items, int page, int size, long total) {
        public static RequestListResponse from(AccessRequestPage page) {
            return new RequestListResponse(page.items().stream().map(RequestSummaryResponse::from).toList(),
                    page.page(), page.size(), page.total());
        }
    }

    public record RequestSummaryResponse(
            String id, String entitlementId, ResourceType resourceType, String resourceName,
            DecisionStatus decisionStatus, ProvisioningStatus provisioningStatus,
            String createdAt, String provisioningClosedAt, Long durationSeconds, boolean permanent) {
        public static RequestSummaryResponse from(AccessRequest request) {
            return new RequestSummaryResponse(request.id(), request.entitlementId(), request.resourceType(),
                    request.resourceNameSnapshot(), request.decisionStatus(), request.provisioningStatus(),
                    request.createdAt().toString(),
                    request.provisioningClosedAt() == null ? null : request.provisioningClosedAt().toString(),
                    request.requestedDurationSeconds(), request.permanent());
        }
    }

    public record RequestDetailResponse(
            String id, String entitlementId, ResourceType resourceType, String resourceName,
            DecisionStatus decisionStatus, ProvisioningStatus provisioningStatus, String createdAt,
            String provisioningClosedAt, String justification, DecisionResponse decision,
            List<RequestHistoryEntryResponse> history, Long durationSeconds, boolean permanent) {
        public static RequestDetailResponse from(AccessRequestDetails details,
                Function<String, String> approverNameResolver) {
            AccessRequest request = details.request();
            DecisionResponse decision = request.approverId() == null ? null
                    : new DecisionResponse(request.approverId(), approverNameResolver.apply(request.approverId()),
                            request.decisionComment(),
                            request.decidedAt().toString());
            return new RequestDetailResponse(request.id(), request.entitlementId(), request.resourceType(),
                    request.resourceNameSnapshot(), request.decisionStatus(), request.provisioningStatus(),
                    request.createdAt().toString(),
                    request.provisioningClosedAt() == null ? null : request.provisioningClosedAt().toString(),
                    request.justification(), decision,
                    details.history().stream().map(RequestHistoryEntryResponse::from).toList(),
                    request.requestedDurationSeconds(), request.permanent());
        }
    }

    public record DecisionResponse(String approverId, String approverName, String comment, String decidedAt) {
        public DecisionResponse(String approverId, String comment, String decidedAt) {
            this(approverId, null, comment, decidedAt);
        }
    }

    public record RequestHistoryEntryResponse(String type, String occurredAt) {
        public static RequestHistoryEntryResponse from(AccessRequestEvent event) {
            return new RequestHistoryEntryResponse(event.type().name(), event.occurredAt().toString());
        }
    }
}
