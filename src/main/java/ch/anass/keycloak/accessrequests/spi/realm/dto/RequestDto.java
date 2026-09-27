package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestDetails;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;

import java.util.List;

/** JSON payloads for requester-owned access requests. */
public final class RequestDto {

    private RequestDto() {
    }

    public record RequestSubmission(String entitlementId, String justification) {
    }

    public record RequestResponse(
            String id, String entitlementId, DecisionStatus decisionStatus,
            ProvisioningStatus provisioningStatus) {
        public static RequestResponse from(AccessRequest request) {
            return new RequestResponse(request.id(), request.entitlementId(),
                    request.decisionStatus(), request.provisioningStatus());
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
            String createdAt, String provisioningClosedAt) {
        public static RequestSummaryResponse from(AccessRequest request) {
            return new RequestSummaryResponse(request.id(), request.entitlementId(), request.resourceType(),
                    request.resourceNameSnapshot(), request.decisionStatus(), request.provisioningStatus(),
                    request.createdAt().toString(),
                    request.provisioningClosedAt() == null ? null : request.provisioningClosedAt().toString());
        }
    }

    public record RequestDetailResponse(
            String id, String entitlementId, ResourceType resourceType, String resourceName,
            DecisionStatus decisionStatus, ProvisioningStatus provisioningStatus, String createdAt,
            String provisioningClosedAt, String justification, DecisionResponse decision,
            List<RequestHistoryEntryResponse> history) {
        public static RequestDetailResponse from(AccessRequestDetails details) {
            AccessRequest request = details.request();
            DecisionResponse decision = request.approverId() == null ? null
                    : new DecisionResponse(request.approverId(), request.decisionComment(),
                            request.decidedAt().toString());
            return new RequestDetailResponse(request.id(), request.entitlementId(), request.resourceType(),
                    request.resourceNameSnapshot(), request.decisionStatus(), request.provisioningStatus(),
                    request.createdAt().toString(),
                    request.provisioningClosedAt() == null ? null : request.provisioningClosedAt().toString(),
                    request.justification(), decision,
                    details.history().stream().map(RequestHistoryEntryResponse::from).toList());
        }
    }

    public record DecisionResponse(String approverId, String comment, String decidedAt) {
    }

    public record RequestHistoryEntryResponse(String type, String occurredAt) {
        public static RequestHistoryEntryResponse from(AccessRequestEvent event) {
            return new RequestHistoryEntryResponse(event.type().name(), event.occurredAt().toString());
        }
    }
}
