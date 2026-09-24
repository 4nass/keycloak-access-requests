package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;

import java.util.List;
import java.util.Objects;

/**
 * Maps selected access request lifecycle events to user-facing notifications.
 */
public final class AccessRequestNotificationPolicy {

    public List<AccessRequestNotification> notificationsFor(
            AccessRequest request,
            Entitlement entitlement,
            AccessRequestEvent event) {
        requireConsistentContext(request, entitlement, event);

        return switch (event.type()) {
            case REQUEST_CREATED -> List.of(notification(
                    AccessRequestNotificationType.REQUEST_SUBMITTED,
                    AccessRequestNotificationRecipientType.REALM_ROLE,
                    entitlement.approverRoleId(),
                    request,
                    entitlement,
                    event));
            case REQUEST_APPROVED -> List.of(notification(
                    AccessRequestNotificationType.REQUEST_APPROVED,
                    AccessRequestNotificationRecipientType.USER,
                    request.requesterId(),
                    request,
                    entitlement,
                    event));
            case REQUEST_REJECTED -> List.of(notification(
                    AccessRequestNotificationType.REQUEST_REJECTED,
                    AccessRequestNotificationRecipientType.USER,
                    request.requesterId(),
                    request,
                    entitlement,
                    event));
            case PROVISIONING_FAILED -> List.of(notification(
                    AccessRequestNotificationType.PROVISIONING_FAILED,
                    AccessRequestNotificationRecipientType.USER,
                    request.requesterId(),
                    request,
                    entitlement,
                    event));
            case PROVISIONING_CLOSED -> List.of(
                    notification(
                            AccessRequestNotificationType.PROVISIONING_CLOSED,
                            AccessRequestNotificationRecipientType.USER,
                            request.requesterId(), request, entitlement, event),
                    notification(
                            AccessRequestNotificationType.PROVISIONING_CLOSED,
                            AccessRequestNotificationRecipientType.REALM_ROLE,
                            entitlement.approverRoleId(), request, entitlement, event));
            case REQUEST_CANCELED, PROVISIONING_STARTED, PROVISIONING_SUCCEEDED -> List.of();
        };
    }

    private static AccessRequestNotification notification(
            AccessRequestNotificationType type,
            AccessRequestNotificationRecipientType recipientType,
            String recipientId,
            AccessRequest request,
            Entitlement entitlement,
            AccessRequestEvent event) {
        return new AccessRequestNotification(
                type,
                recipientType,
                recipientId,
                request,
                entitlement,
                event);
    }

    private static void requireConsistentContext(
            AccessRequest request,
            Entitlement entitlement,
            AccessRequestEvent event) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        Objects.requireNonNull(event, "event must not be null");
        if (!request.realmId().equals(entitlement.realmId())
                || !request.entitlementId().equals(entitlement.id())
                || !request.realmId().equals(event.realmId())
                || !request.id().equals(event.requestId())) {
            throw new IllegalArgumentException("request, entitlement, and event must describe the same request context");
        }
    }
}
