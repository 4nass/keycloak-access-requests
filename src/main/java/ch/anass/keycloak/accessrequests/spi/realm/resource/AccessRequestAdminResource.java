package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.request.InvalidProvisioningRetryException;
import ch.anass.keycloak.accessrequests.core.domain.request.InvalidProvisioningClosureException;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.ConcurrentRequestModificationException;
import ch.anass.keycloak.accessrequests.core.service.RequestNotFoundException;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestNotificationOutboxRepository;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApiDto.AdminCapabilitiesResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.NotificationDto.NotificationDeliverySummaryResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ProvisioningDto.ProvisioningClosureResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ProvisioningDto.ProvisioningClosureSubmission;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.RequestResponse;
import jakarta.ws.rs.core.Response;
import java.time.Instant;

final class AccessRequestAdminResource extends AccessRequestEndpointSupport {

    AccessRequestAdminResource(AccessRequestServiceFactory services) {
        super(services);
    }

    public AdminCapabilitiesResponse adminCapabilities() {
        requireAccessRequestManager();
        return new AdminCapabilitiesResponse(true, true, true);
    }

    public Response listAuditEvents(
            String from,
            String to,
            String type,
            String actorId,
            String requestId,
            int page,
            int size) {
        AccessRequestManager manager = requireAccessRequestManager();
        try {
            var result = historyReader().findAll(
                    manager.realm().getId(), parseInstant(from, "from"), parseInstant(to, "to"),
                    parseEnum(AccessRequestEventType.class, type, "type"), actorId, requestId, page, size);
            return Response.ok(AdminResponseMapper.auditEvents(result)).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_AUDIT_EVENT_QUERY", exception.getMessage(), null);
        }
    }

    public Response administrativeRequestDetails(String requestId,
            int historyPage,
            int historySize) {
        AccessRequestManager manager = requireAccessRequestManager();
        AccessRequest request = requestRepository()
                .findById(manager.realm().getId(), requestId)
                .orElse(null);
        if (request == null) {
            return error(Response.Status.NOT_FOUND, "REQUEST_NOT_FOUND", null, requestId);
        }
        try {
            var history = historyReader()
                    .findPageByRequestId(manager.realm().getId(), requestId, historyPage, historySize);
            return Response.ok(AdminResponseMapper.requestDetail(request, history)).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_AUDIT_EVENT_QUERY", exception.getMessage(), requestId);
        }
    }

    public Response listFailedNotificationDeliveries(
            int page,
            int size) {
        AccessRequestManager manager = requireAccessRequestManager();
        try {
            return Response.ok(AdminResponseMapper.notificationDeliveries(
                    notificationOutboxRepository().findFailed(manager.realm().getId(), page, size))).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_NOTIFICATION_DELIVERY_QUERY", exception.getMessage(), null);
        }
    }

    public Response listFailedProvisioningRequests(
            int page,
            int size,
            String state) {
        AccessRequestManager manager = requireAccessRequestManager();
        JpaAccessRequestRepository repository = requestRepository();
        try {
            if (!"OPEN".equals(state) && !"CLOSED".equals(state)) {
                throw new IllegalArgumentException("state must be OPEN or CLOSED");
            }
            JpaAccessRequestRepository.FailedProvisioningPage failedRequests =
                    "CLOSED".equals(state)
                            ? repository.findClosedProvisioning(manager.realm().getId(), page, size)
                            : repository.findFailedProvisioning(manager.realm().getId(), page, size);
            return Response.ok(AdminResponseMapper.failedProvisioning(failedRequests)).build();
        } catch (IllegalArgumentException exception) {
            return error(
                    Response.Status.BAD_REQUEST,
                    "INVALID_PROVISIONING_FAILURE_QUERY",
                    exception.getMessage(),
                    null);
        }
    }

    public NotificationDeliverySummaryResponse notificationDeliverySummary() {
        AccessRequestManager manager = requireAccessRequestManager();
        return AdminResponseMapper.notificationSummary(
                notificationOutboxRepository().summarize(manager.realm().getId()));
    }

    public Response retryFailedNotificationDelivery(String deliveryId) {
        AccessRequestManager manager = requireAccessRequestManager();
        JpaAccessRequestNotificationOutboxRepository.RetryFailedResult result = transaction().execute(() ->
                notificationOutboxRepository().retryFailed(manager.realm().getId(), deliveryId, Instant.now()));
        return switch (result) {
            case RETRIED -> Response.noContent().build();
            case NOT_FOUND -> error(Response.Status.NOT_FOUND, "NOTIFICATION_DELIVERY_NOT_FOUND", null, deliveryId);
            case NOT_FAILED -> error(
                    Response.Status.CONFLICT,
                    "NOTIFICATION_DELIVERY_NOT_FAILED",
                    "Notification delivery is no longer failed",
                    deliveryId);
        };
    }

    public Response retryFailedProvisioning(String requestId) {
        AccessRequestManager manager = requireAccessRequestManager();
        try {
            AccessRequest retried = requestService(manager.realm(), manager.user()).retryProvisioning(
                    manager.realm().getId(), requestId, manager.user().getId());
            return Response.ok(RequestResponse.from(retried)).build();
        } catch (RequestNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "REQUEST_NOT_FOUND", exception.getMessage(), requestId);
        } catch (EntitlementNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "ENTITLEMENT_NOT_FOUND", exception.getMessage(), requestId);
        } catch (InvalidProvisioningRetryException exception) {
            return error(Response.Status.CONFLICT, "INVALID_PROVISIONING_RETRY", exception.getMessage(), requestId);
        } catch (ConcurrentRequestModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_MODIFICATION", exception.getMessage(), requestId);
        }
    }

    public Response closeFailedProvisioning(
            String requestId, ProvisioningClosureSubmission submission) {
        AccessRequestManager manager = requireAccessRequestManager();
        if (submission == null || submission.reason() == null
                || submission.reason().strip().length() < 10
                || submission.reason().strip().length() > 1_000) {
            return error(Response.Status.BAD_REQUEST, "INVALID_PROVISIONING_CLOSURE_REASON",
                    "reason must contain 10 to 1000 characters", requestId);
        }
        try {
            AccessRequest closed = requestService(manager.realm(), manager.user()).closeFailedProvisioning(
                    manager.realm().getId(), requestId, manager.user().getId(), submission.reason());
            return Response.ok(ProvisioningClosureResponse.from(closed)).build();
        } catch (RequestNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "REQUEST_NOT_FOUND", exception.getMessage(), requestId);
        } catch (InvalidProvisioningClosureException exception) {
            return error(Response.Status.CONFLICT, "INVALID_PROVISIONING_CLOSURE", exception.getMessage(), requestId);
        } catch (ConcurrentRequestModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_MODIFICATION", exception.getMessage(), requestId);
        }
    }
}
