package ch.anass.keycloak.accessrequests.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit snapshot for a catalog policy change.
 */
public final class EntitlementAuditEvent {

    private final String id;
    private final String entitlementId;
    private final String realmId;
    private final EntitlementAuditEventType type;
    private final String actorId;
    private final Instant occurredAt;
    private final ResourceType resourceType;
    private final String resourceId;
    private final String displayName;
    private final String description;
    private final RiskLevel riskLevel;
    private final String approverRoleId;
    private final boolean requestable;
    private final long version;

    private EntitlementAuditEvent(
            String id,
            String entitlementId,
            String realmId,
            EntitlementAuditEventType type,
            String actorId,
            Instant occurredAt,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            boolean requestable,
            long version) {
        this.id = requireText(id, "id");
        this.entitlementId = requireText(entitlementId, "entitlementId");
        this.realmId = requireText(realmId, "realmId");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.actorId = requireText(actorId, "actorId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        this.resourceId = requireText(resourceId, "resourceId");
        this.displayName = requireText(displayName, "displayName");
        this.description = requireText(description, "description");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.approverRoleId = requireText(approverRoleId, "approverRoleId");
        this.requestable = requestable;
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
    }

    public static EntitlementAuditEvent created(Entitlement entitlement, String actorId) {
        return from(entitlement, EntitlementAuditEventType.ENTITLEMENT_CREATED, actorId);
    }

    public static EntitlementAuditEvent updated(Entitlement entitlement, String actorId) {
        return from(entitlement, EntitlementAuditEventType.ENTITLEMENT_UPDATED, actorId);
    }

    private static EntitlementAuditEvent from(
            Entitlement entitlement,
            EntitlementAuditEventType type,
            String actorId) {
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        return new EntitlementAuditEvent(
                UUID.randomUUID().toString(),
                entitlement.id(),
                entitlement.realmId(),
                type,
                actorId,
                entitlement.updatedAt(),
                entitlement.resourceType(),
                entitlement.resourceId(),
                entitlement.displayName(),
                entitlement.description(),
                entitlement.riskLevel(),
                entitlement.approverRoleId(),
                entitlement.requestable(),
                entitlement.version());
    }

    public String id() {
        return id;
    }

    public String entitlementId() {
        return entitlementId;
    }

    public String realmId() {
        return realmId;
    }

    public EntitlementAuditEventType type() {
        return type;
    }

    public String actorId() {
        return actorId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public ResourceType resourceType() {
        return resourceType;
    }

    public String resourceId() {
        return resourceId;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }

    public String approverRoleId() {
        return approverRoleId;
    }

    public boolean requestable() {
        return requestable;
    }

    public long version() {
        return version;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
