package ch.anass.keycloak.accessrequests.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AccessRequestEvent {

    private final String id;
    private final String requestId;
    private final String realmId;
    private final AccessRequestEventType type;
    private final String actorId;
    private final Instant occurredAt;
    private final String comment;

    private AccessRequestEvent(
            String id,
            String requestId,
            String realmId,
            AccessRequestEventType type,
            String actorId,
            Instant occurredAt,
            String comment) {
        this.id = requireText(id, "id");
        this.requestId = requireText(requestId, "requestId");
        this.realmId = requireText(realmId, "realmId");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.actorId = requireText(actorId, "actorId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.comment = comment;
    }

    public static AccessRequestEvent created(AccessRequest request, String actorId, Instant occurredAt) {
        return from(request, AccessRequestEventType.REQUEST_CREATED, actorId, occurredAt, null);
    }

    public static AccessRequestEvent canceled(
            AccessRequest request,
            String actorId,
            Instant occurredAt) {
        return from(request, AccessRequestEventType.REQUEST_CANCELED, actorId, occurredAt, null);
    }

    public static AccessRequestEvent approved(
            AccessRequest request,
            String actorId,
            Instant occurredAt,
            String comment) {
        return from(request, AccessRequestEventType.REQUEST_APPROVED, actorId, occurredAt, comment);
    }

    public static AccessRequestEvent rejected(
            AccessRequest request,
            String actorId,
            Instant occurredAt,
            String comment) {
        return from(request, AccessRequestEventType.REQUEST_REJECTED, actorId, occurredAt, comment);
    }

    private static AccessRequestEvent from(
            AccessRequest request,
            AccessRequestEventType type,
            String actorId,
            Instant occurredAt,
            String comment) {
        Objects.requireNonNull(request, "request must not be null");
        return new AccessRequestEvent(
                UUID.randomUUID().toString(),
                request.id(),
                request.realmId(),
                type,
                actorId,
                occurredAt,
                comment);
    }

    public String id() {
        return id;
    }

    public String requestId() {
        return requestId;
    }

    public String realmId() {
        return realmId;
    }

    public AccessRequestEventType type() {
        return type;
    }

    public String actorId() {
        return actorId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String comment() {
        return comment;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
