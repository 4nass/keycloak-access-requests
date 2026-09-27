package ch.anass.keycloak.accessrequests.spi.realm.dto;

import java.util.List;

/** JSON payloads for notification delivery administration. */
public final class NotificationDto {

    private NotificationDto() {
    }

    public record NotificationDeliveryListResponse(
            List<NotificationDeliveryResponse> items, int page, int size, long total) {
    }

    public record NotificationDeliveryResponse(
            String id, String requestId, String entitlementId, String recipientId,
            String recipientType, String notificationType, int attemptCount, String lastAttemptAt) {
    }

    public record NotificationDeliverySummaryResponse(
            long pending, long processing, long delivered, long discarded, long failed) {
    }
}
