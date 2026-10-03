package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;

import java.util.List;

/** JSON payloads for failed provisioning administration. */
public final class ProvisioningDto {

    private ProvisioningDto() {
    }

    public record ProvisioningClosureSubmission(String reason) {
    }

    public record ProvisioningClosureResponse(
            String id, DecisionStatus decisionStatus, ProvisioningStatus provisioningStatus,
            String closedAt, String closedBy, String reason) {
        public static ProvisioningClosureResponse from(AccessRequest request) {
            return new ProvisioningClosureResponse(request.id(), request.decisionStatus(),
                    request.provisioningStatus(), request.provisioningClosedAt().toString(),
                    request.provisioningClosedBy(), request.provisioningClosureReason());
        }
    }

    public record FailedProvisioningRequestListResponse(
            List<FailedProvisioningRequestResponse> items, int page, int size, long total) {
    }

    public record FailedProvisioningRequestResponse(
            String id, String requesterId, String entitlementId, ResourceType resourceType,
            String resourceName, DecisionStatus decisionStatus, ProvisioningStatus provisioningStatus,
            String updatedAt, ProvisioningFailureCode failureCode, String closedAt,
            String closedBy, String closureReason,
            String requesterName, String entitlementName, String closedByName) {
    }
}
