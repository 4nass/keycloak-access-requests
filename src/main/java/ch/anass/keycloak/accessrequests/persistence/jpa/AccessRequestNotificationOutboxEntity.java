package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * A transactionally persisted email delivery waiting to be handled outside the request transaction.
 */
@Entity
@Table(
        name = "AR_NOTIFICATION_OUTBOX",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_NOTIFICATION_OUTBOX_DELIVERY",
                columnNames = "DELIVERY_KEY"))
public class AccessRequestNotificationOutboxEntity {

    @Id
    @Column(name = "ID", nullable = false, length = 36)
    private String id;

    @Column(name = "DELIVERY_KEY", nullable = false, length = 255)
    private String deliveryKey;

    @Column(name = "EVENT_ID", nullable = false, length = 36)
    private String eventId;

    @Column(name = "REQUEST_ID", nullable = false, length = 36)
    private String requestId;

    @Column(name = "ENTITLEMENT_ID", nullable = false, length = 36)
    private String entitlementId;

    @Column(name = "REALM_ID", nullable = false, length = 255)
    private String realmId;

    @Column(name = "RECIPIENT_ID", nullable = false, length = 255)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "RECIPIENT_TYPE", nullable = false, length = 20)
    private AccessRequestNotificationRecipientType recipientType;

    @Enumerated(EnumType.STRING)
    @Column(name = "NOTIFICATION_TYPE", nullable = false, length = 50)
    private AccessRequestNotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATE", nullable = false, length = 20)
    private AccessRequestNotificationOutboxState state;

    @Column(name = "ATTEMPT_COUNT", nullable = false)
    private int attemptCount;

    @Column(name = "NEXT_ATTEMPT_TIMESTAMP", nullable = false)
    private long nextAttemptTimestamp;

    @Column(name = "LAST_ATTEMPT_TIMESTAMP")
    private Long lastAttemptTimestamp;

    @Column(name = "LEASE_UNTIL_TIMESTAMP")
    private Long leaseUntilTimestamp;

    @Column(name = "PROCESSOR_ID", length = 36)
    private String processorId;

    @Column(name = "DELIVERED_TIMESTAMP")
    private Long deliveredTimestamp;

    @Version
    @Column(name = "VERSION", nullable = false)
    private long version;

    protected AccessRequestNotificationOutboxEntity() {
    }

    private AccessRequestNotificationOutboxEntity(
            AccessRequestNotification notification,
            AccessRequestNotificationRecipientType recipientType,
            String recipientId,
            Instant queuedAt) {
        this.id = UUID.randomUUID().toString();
        this.eventId = notification.event().id();
        this.requestId = notification.request().id();
        this.entitlementId = notification.entitlement().id();
        this.realmId = notification.request().realmId();
        this.recipientId = requireText(recipientId, "recipientId");
        this.recipientType = Objects.requireNonNull(recipientType, "recipientType must not be null");
        this.notificationType = notification.type();
        this.deliveryKey = deliveryKey(eventId, notificationType, this.recipientType, this.recipientId);
        this.state = AccessRequestNotificationOutboxState.PENDING;
        this.nextAttemptTimestamp = Objects.requireNonNull(queuedAt, "queuedAt must not be null").toEpochMilli();
    }

    static AccessRequestNotificationOutboxEntity queue(
            AccessRequestNotification notification,
            AccessRequestNotificationRecipientType recipientType,
            String recipientId,
            Instant queuedAt) {
        return new AccessRequestNotificationOutboxEntity(notification, recipientType, recipientId, queuedAt);
    }

    static String deliveryKey(
            String eventId,
            AccessRequestNotificationType notificationType,
            AccessRequestNotificationRecipientType recipientType,
            String recipientId) {
        AccessRequestNotificationRecipientType requiredRecipientType =
                Objects.requireNonNull(recipientType, "recipientType");
        String recipientTypeDiscriminator = requiredRecipientType == AccessRequestNotificationRecipientType.USER
                ? ""
                : requiredRecipientType + ":";
        String value = requireText(eventId, "eventId") + ':'
                + Objects.requireNonNull(notificationType, "notificationType") + ':'
                + recipientTypeDiscriminator
                + requireText(recipientId, "recipientId");
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    public String id() {
        return id;
    }

    public String eventId() {
        return eventId;
    }

    public String requestId() {
        return requestId;
    }

    public String entitlementId() {
        return entitlementId;
    }

    public String realmId() {
        return realmId;
    }

    public String recipientId() {
        return recipientId;
    }

    public AccessRequestNotificationRecipientType recipientType() {
        return recipientType;
    }

    public AccessRequestNotificationType notificationType() {
        return notificationType;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public AccessRequestNotificationOutboxState state() {
        return state;
    }

    public Instant lastAttemptAt() {
        return Instant.ofEpochMilli(lastAttemptTimestamp == null ? nextAttemptTimestamp : lastAttemptTimestamp);
    }

    public String deliveryKey() {
        return deliveryKey;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
