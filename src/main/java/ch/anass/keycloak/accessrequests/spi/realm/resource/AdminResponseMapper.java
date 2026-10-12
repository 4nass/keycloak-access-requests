package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestNotificationOutboxEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestHistoryReader;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAdminIncidentReader;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestNotificationOutboxRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaGrantRevocationFailureRepository;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RevocationDto.RevocationFailureListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RevocationDto.RevocationFailureResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.IncidentDto.IncidentListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.IncidentDto.IncidentResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.AuditDto.AdminRequestDetailResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.AuditDto.AuditEventListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.NotificationDto.NotificationDeliveryListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.NotificationDto.NotificationDeliveryResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.NotificationDto.NotificationDeliverySummaryResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ProvisioningDto.FailedProvisioningRequestListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ProvisioningDto.FailedProvisioningRequestResponse;

/** Converts persistence query results at the REST boundary; payload types stay persistence-agnostic. */
final class AdminResponseMapper {

    private AdminResponseMapper() {
    }

    static AuditEventListResponse auditEvents(JpaAccessRequestHistoryReader.AuditEventPage result,
            AdminNameLookup names) {
        return AuditEventListResponse.from(result.items(), result.page(), result.size(), result.total(),
                names::user, names::request);
    }

    static AdminRequestDetailResponse requestDetail(
            AccessRequest request, JpaAccessRequestHistoryReader.AuditEventPage history,
            AccessGrant grant, AdminNameLookup names) {
        return AdminRequestDetailResponse.from(request, history.items(),
                history.page(), history.size(), history.total(), names.user(request.requesterId()),
                names.entitlement(request.entitlementId(), request.resourceNameSnapshot()), names::user, grant);
    }

    static FailedProvisioningRequestListResponse failedProvisioning(
            JpaAccessRequestRepository.FailedProvisioningPage page, AdminNameLookup names) {
        return new FailedProvisioningRequestListResponse(
                page.items().stream().map(item -> failedProvisioningRequest(item, names)).toList(),
                page.page(), page.size(), page.total());
    }

    private static FailedProvisioningRequestResponse failedProvisioningRequest(
            JpaAccessRequestRepository.FailedProvisioningRequest request, AdminNameLookup names) {
        return new FailedProvisioningRequestResponse(request.id(), request.requesterId(),
                request.entitlementId(), request.resourceType(), request.resourceName(),
                request.decisionStatus(), request.provisioningStatus(), request.updatedAt().toString(),
                request.failureCode(), request.closedAt() == null ? null : request.closedAt().toString(),
                request.closedBy(), request.closureReason(), names.user(request.requesterId()),
                names.entitlement(request.entitlementId(), request.resourceName()),
                names.user(request.closedBy()));
    }

    static RevocationFailureListResponse revocationFailures(
            JpaGrantRevocationFailureRepository.FailurePage page, AdminNameLookup names) {
        return new RevocationFailureListResponse(page.items().stream()
                .map(item -> revocationFailure(item, names)).toList(),
                page.page(), page.size(), page.total());
    }

    static IncidentListResponse incidents(JpaAdminIncidentReader.IncidentPage page, AdminNameLookup names) {
        return new IncidentListResponse(page.items().stream().map(item -> switch (item.type()) {
            case NOTIFICATION -> new IncidentResponse(item.type().name(), item.id(),
                    item.occurredAt().toString(), "OPEN", notificationDelivery(item.notification(), names),
                    null, null);
            case PROVISIONING -> new IncidentResponse(item.type().name(), item.id(),
                    item.occurredAt().toString(), item.provisioning().closedAt() == null ? "OPEN" : "CLOSED",
                    null, failedProvisioningRequest(item.provisioning(), names), null);
            case REVOCATION -> new IncidentResponse(item.type().name(), item.id(),
                    item.occurredAt().toString(), item.revocation().failure().resolvedAt() == null ? "OPEN" : "CLOSED",
                    null, null, revocationFailure(item.revocation(), names));
            case ALL -> throw new IllegalStateException("ALL cannot be an incident item");
        }).toList(), page.size(), page.nextCursor());
    }

    private static RevocationFailureResponse revocationFailure(
            JpaGrantRevocationFailureRepository.FailureItem item, AdminNameLookup names) {
        var grant = item.grant();
        var failure = item.failure();
        return new RevocationFailureResponse(grant.requestId(), grant.requesterId(), grant.entitlementId(),
                grant.resourceType(), grant.resourceId(), grant.deliveryGroupId(),
                grant.expiresAt() == null ? null : grant.expiresAt().toString(),
                failure.code(), failure.attemptCount(), failure.firstFailedAt().toString(),
                failure.lastFailedAt().toString(), failure.nextAttemptAt().toString(),
                failure.resolvedAt() == null ? null : failure.resolvedAt().toString(),
                names.user(grant.requesterId()), names.entitlement(grant.entitlementId(), null),
                grant.resourceType() == ResourceType.GROUP
                        ? null : names.resource(grant.resourceType(), grant.resourceId()));
    }

    static NotificationDeliveryListResponse notificationDeliveries(
            JpaAccessRequestNotificationOutboxRepository.NotificationOutboxPage page,
            AdminNameLookup names) {
        return new NotificationDeliveryListResponse(
                page.items().stream().map(entry -> notificationDelivery(entry, names)).toList(),
                page.page(), page.size(), page.total());
    }

    private static NotificationDeliveryResponse notificationDelivery(AccessRequestNotificationOutboxEntity entry,
            AdminNameLookup names) {
        return new NotificationDeliveryResponse(entry.id(), entry.requestId(), entry.entitlementId(),
                entry.recipientId(), entry.recipientType().name(), entry.notificationType().name(),
                entry.attemptCount(), entry.lastAttemptAt() == null ? null : entry.lastAttemptAt().toString(),
                names.request(entry.requestId()), names.entitlement(entry.entitlementId(), null),
                "USER".equals(entry.recipientType().name()) ? names.user(entry.recipientId())
                        : names.role(entry.recipientId()));
    }

    static NotificationDeliverySummaryResponse notificationSummary(
            JpaAccessRequestNotificationOutboxRepository.NotificationOutboxSummary summary) {
        return new NotificationDeliverySummaryResponse(summary.pending(), summary.processing(),
                summary.delivered(), summary.discarded(), summary.failed());
    }
}
