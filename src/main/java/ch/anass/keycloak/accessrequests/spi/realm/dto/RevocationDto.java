package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
import java.util.List;

/** Administrative representation of package membership removal failures. */
public final class RevocationDto {

    private RevocationDto() {
    }

    public record RevocationFailureResponse(String requestId, String requesterId, String entitlementId,
            ResourceType resourceType, String resourceId, String deliveryGroupId, String expiresAt,
            GrantRevocationFailureCode failureCode, int attemptCount, String firstFailedAt,
            String lastFailedAt, String nextAttemptAt, String resolvedAt) {
    }

    public record RevocationFailureListResponse(
            List<RevocationFailureResponse> items, int page, int size, long total) {
    }

    public record RevocationRetryResponse(String requestId, String status,
            GrantRevocationFailureCode failureCode) {
    }

    public record RevocationResolutionSubmission(String reason) {
    }
}
