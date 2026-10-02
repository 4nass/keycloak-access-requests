package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestNotificationOutboxEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestHistoryReader;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestNotificationOutboxRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaGrantRevocationFailureRepository;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RevocationDto.RevocationFailureListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RevocationDto.RevocationFailureResponse;
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

    static AuditEventListResponse auditEvents(JpaAccessRequestHistoryReader.AuditEventPage result) {
        return AuditEventListResponse.from(result.items(), result.page(), result.size(), result.total());
    }

    static AdminRequestDetailResponse requestDetail(
            AccessRequest request, JpaAccessRequestHistoryReader.AuditEventPage history) {
        return AdminRequestDetailResponse.from(request, history.items(),
                history.page(), history.size(), history.total());
    }

    static FailedProvisioningRequestListResponse failedProvisioning(
            JpaAccessRequestRepository.FailedProvisioningPage page) {
        return new FailedProvisioningRequestListResponse(
                page.items().stream().map(AdminResponseMapper::failedProvisioningRequest).toList(),
                page.page(), page.size(), page.total());
    }

    private static FailedProvisioningRequestResponse failedProvisioningRequest(
            JpaAccessRequestRepository.FailedProvisioningRequest request) {
        return new FailedProvisioningRequestResponse(request.id(), request.requesterId(),
                request.entitlementId(), request.resourceType(), request.resourceName(),
                request.decisionStatus(), request.provisioningStatus(), request.updatedAt().toString(),
                request.failureCode(), request.closedAt() == null ? null : request.closedAt().toString(),
                request.closedBy(), request.closureReason());
    }

    static RevocationFailureListResponse revocationFailures(
            JpaGrantRevocationFailureRepository.FailurePage page) {
        return new RevocationFailureListResponse(page.items().stream().map(item -> {
            var grant = item.grant();
            var failure = item.failure();
            return new RevocationFailureResponse(grant.requestId(), grant.requesterId(), grant.entitlementId(),
                    grant.resourceType(), grant.resourceId(), grant.deliveryGroupId(), grant.expiresAt().toString(),
                    failure.code(), failure.attemptCount(), failure.firstFailedAt().toString(),
                    failure.lastFailedAt().toString(), failure.nextAttemptAt().toString(),
                    failure.resolvedAt() == null ? null : failure.resolvedAt().toString());
        }).toList(), page.page(), page.size(), page.total());
    }

    static NotificationDeliveryListResponse notificationDeliveries(
            JpaAccessRequestNotificationOutboxRepository.NotificationOutboxPage page) {
        return new NotificationDeliveryListResponse(
                page.items().stream().map(AdminResponseMapper::notificationDelivery).toList(),
                page.page(), page.size(), page.total());
    }

    private static NotificationDeliveryResponse notificationDelivery(AccessRequestNotificationOutboxEntity entry) {
        return new NotificationDeliveryResponse(entry.id(), entry.requestId(), entry.entitlementId(),
                entry.recipientId(), entry.recipientType().name(), entry.notificationType().name(),
                entry.attemptCount(), entry.lastAttemptAt() == null ? null : entry.lastAttemptAt().toString());
    }

    static NotificationDeliverySummaryResponse notificationSummary(
            JpaAccessRequestNotificationOutboxRepository.NotificationOutboxSummary summary) {
        return new NotificationDeliverySummaryResponse(summary.pending(), summary.processing(),
                summary.delivered(), summary.discarded(), summary.failed());
    }
}
