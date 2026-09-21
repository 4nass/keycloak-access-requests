package ch.anass.keycloak.accessrequests.core.domain;

import java.util.Objects;

/**
 * A notification to be delivered by an infrastructure adapter.
 */
public record AccessRequestNotification(
        AccessRequestNotificationType type,
        AccessRequestNotificationRecipientType recipientType,
        String recipientId,
        AccessRequest request,
        Entitlement entitlement,
        AccessRequestEvent event) {

    public AccessRequestNotification {
        type = Objects.requireNonNull(type, "type must not be null");
        recipientType = Objects.requireNonNull(recipientType, "recipientType must not be null");
        recipientId = requireText(recipientId, "recipientId");
        request = Objects.requireNonNull(request, "request must not be null");
        entitlement = Objects.requireNonNull(entitlement, "entitlement must not be null");
        event = Objects.requireNonNull(event, "event must not be null");
        if (!request.realmId().equals(entitlement.realmId())
                || !request.entitlementId().equals(entitlement.id())
                || !request.realmId().equals(event.realmId())
                || !request.id().equals(event.requestId())) {
            throw new IllegalArgumentException("request, entitlement, and event must describe the same request context");
        }
        request = request.copy();
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
