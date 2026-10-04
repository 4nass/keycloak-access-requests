package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.DecisionResponse;

import java.util.List;
import java.util.function.Function;
import org.keycloak.models.UserModel;

/** JSON payloads for the administrative request history and audit feed. */
public final class AuditDto {

    private AuditDto() {
    }

    public record AuditUserListResponse(List<AuditUserResponse> items) {
    }

    public record AuditUserResponse(String id, String name, String username) {
        public static AuditUserResponse from(UserModel user) {
            String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
            String last = user.getLastName() == null ? "" : user.getLastName().trim();
            String fullName = (first + " " + last).trim();
            return new AuditUserResponse(user.getId(), fullName.isEmpty() ? user.getUsername() : fullName,
                    user.getUsername());
        }
    }

    public record AdminRequestDetailResponse(
            String id, String requesterId, String entitlementId, ResourceType resourceType,
            String resourceName, DecisionStatus decisionStatus, ProvisioningStatus provisioningStatus,
            String createdAt, String provisioningClosedAt, String justification,
            DecisionResponse decision, List<AdminRequestHistoryEntryResponse> history,
            int historyPage, int historySize, long historyTotal,
            String requesterName, String entitlementName, String approverName,
            AdminGrantResponse grant) {
        public static AdminRequestDetailResponse from(AccessRequest request, List<AccessRequestEvent> events,
                int page, int size, long total, String requesterName, String entitlementName,
                Function<String, String> userName, AccessGrant grant) {
            DecisionResponse decision = request.approverId() == null ? null
                    : new DecisionResponse(request.approverId(), request.decisionComment(),
                            request.decidedAt().toString());
            return new AdminRequestDetailResponse(request.id(), request.requesterId(),
                    request.entitlementId(), request.resourceType(), request.resourceNameSnapshot(),
                    request.decisionStatus(), request.provisioningStatus(), request.createdAt().toString(),
                    request.provisioningClosedAt() == null ? null : request.provisioningClosedAt().toString(),
                    request.justification(), decision,
                    events.stream().map(event -> AdminRequestHistoryEntryResponse.from(event,
                            userName.apply(event.actorId()))).toList(), page, size, total,
                    requesterName, entitlementName, userName.apply(request.approverId()),
                    grant == null ? null : AdminGrantResponse.from(grant));
        }
    }

    public record AdminGrantResponse(String origin, String revocationState, String expiresAt,
            boolean manuallyRevocable) {
        static AdminGrantResponse from(AccessGrant grant) {
            return new AdminGrantResponse(grant.origin().name(), grant.revocationState().name(),
                    grant.expiresAt() == null ? null : grant.expiresAt().toString(), grant.canManuallyRevoke());
        }
    }

    public record AdminRequestHistoryEntryResponse(
            String type, String actorId, String occurredAt,
            ProvisioningFailureCode failureCode, String closureReason,
            GrantRevocationFailureCode revocationFailureCode, String revocationResolutionReason,
            String actorName) {
        public static AdminRequestHistoryEntryResponse from(AccessRequestEvent event) {
            return from(event, null);
        }

        public static AdminRequestHistoryEntryResponse from(AccessRequestEvent event, String actorName) {
            ProvisioningFailureCode failureCode = event.type() == AccessRequestEventType.PROVISIONING_FAILED
                    ? ProvisioningFailureCode.fromStoredValue(event.metadata()) : null;
            String closureReason = event.type() == AccessRequestEventType.PROVISIONING_CLOSED
                    ? event.comment() : null;
            GrantRevocationFailureCode revocationFailureCode = event.type() == AccessRequestEventType.REVOCATION_FAILED
                    ? GrantRevocationFailureCode.fromStoredValue(event.metadata()) : null;
            String revocationResolutionReason = event.type() == AccessRequestEventType.REVOCATION_SUCCEEDED
                    ? event.comment() : null;
            return new AdminRequestHistoryEntryResponse(event.type().name(), event.actorId(),
                    event.occurredAt().toString(), failureCode, closureReason,
                    revocationFailureCode, revocationResolutionReason, actorName);
        }
    }

    public record AuditEventListResponse(List<AuditEventResponse> items, int page, int size, long total) {
        public static AuditEventListResponse from(List<AccessRequestEvent> events, int page, int size, long total,
                Function<String, String> actorName, Function<String, String> requestName) {
            return new AuditEventListResponse(events.stream().map(event -> AuditEventResponse.from(event,
                    actorName.apply(event.actorId()), requestName.apply(event.requestId()))).toList(),
                    page, size, total);
        }
    }

    public record AuditEventResponse(String id, String requestId, String type, String actorId,
            String occurredAt, String actorName, String requestName) {
        public static AuditEventResponse from(AccessRequestEvent event, String actorName, String requestName) {
            return new AuditEventResponse(event.id(), event.requestId(), event.type().name(),
                    event.actorId(), event.occurredAt().toString(), actorName, requestName);
        }
    }
}
