package ch.anass.keycloak.accessrequests.core.domain.entitlement;

import java.time.Instant;
import java.util.Objects;

public final class Entitlement {

    private final String id;
    private final String realmId;
    private final ResourceType resourceType;
    private final String resourceId;
    private final String displayName;
    private final String description;
    private final RiskLevel riskLevel;
    private final String approverRoleId;
    private final DurationPolicy durationPolicy;
    private final boolean requestable;
    private final boolean exclusiveJit;
    private final boolean autoApproveLowRisk;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private Entitlement(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            DurationPolicy durationPolicy,
            boolean requestable,
            boolean exclusiveJit,
            boolean autoApproveLowRisk,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = requireText(id, "id");
        this.realmId = requireText(realmId, "realmId");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        this.resourceId = requireText(resourceId, "resourceId");
        this.displayName = requireText(displayName, "displayName");
        this.description = requireText(description, "description");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.approverRoleId = requireText(approverRoleId, "approverRoleId");
        this.durationPolicy = Objects.requireNonNull(durationPolicy, "durationPolicy must not be null");
        this.requestable = requestable;
        this.exclusiveJit = exclusiveJit;
        if (autoApproveLowRisk && riskLevel != RiskLevel.LOW) {
            throw new IllegalArgumentException("Auto-approval is only available for LOW risk");
        }
        this.autoApproveLowRisk = autoApproveLowRisk;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
    }

    public static Entitlement create(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            Instant createdAt) {
        return create(id, realmId, resourceType, resourceId, displayName, description,
                riskLevel, approverRoleId, DurationPolicy.defaultsFor(riskLevel), createdAt);
    }

    public static Entitlement create(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            DurationPolicy durationPolicy,
            Instant createdAt) {
        return new Entitlement(
                id,
                realmId,
                resourceType,
                resourceId,
                displayName,
                description,
                riskLevel,
                approverRoleId,
                durationPolicy,
                false,
                false,
                false,
                createdAt,
                createdAt,
                0);
    }

    public static Entitlement rehydrate(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            boolean requestable,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return rehydrate(id, realmId, resourceType, resourceId, displayName, description,
                riskLevel, approverRoleId, DurationPolicy.defaultsFor(riskLevel), requestable,
                createdAt, updatedAt, version);
    }

    public static Entitlement rehydrate(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            DurationPolicy durationPolicy,
            boolean requestable,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Entitlement(
                id,
                realmId,
                resourceType,
                resourceId,
                displayName,
                description,
                riskLevel,
                approverRoleId,
                durationPolicy,
                requestable,
                false,
                false,
                createdAt,
                updatedAt,
                version);
    }

    public static Entitlement rehydrate(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            DurationPolicy durationPolicy,
            boolean requestable,
            boolean exclusiveJit,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return rehydrate(id, realmId, resourceType, resourceId, displayName, description,
                riskLevel, approverRoleId, durationPolicy, requestable, exclusiveJit, false,
                createdAt, updatedAt, version);
    }

    public static Entitlement rehydrate(
            String id, String realmId, ResourceType resourceType, String resourceId,
            String displayName, String description, RiskLevel riskLevel, String approverRoleId,
            DurationPolicy durationPolicy, boolean requestable, boolean exclusiveJit,
            boolean autoApproveLowRisk, Instant createdAt, Instant updatedAt, long version) {
        return new Entitlement(id, realmId, resourceType, resourceId, displayName, description,
                riskLevel, approverRoleId, durationPolicy, requestable, exclusiveJit, autoApproveLowRisk,
                createdAt, updatedAt, version);
    }

    public String id() {
        return id;
    }

    public String realmId() {
        return realmId;
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

    public DurationPolicy durationPolicy() {
        return durationPolicy;
    }

    public boolean requestable() {
        return requestable;
    }

    public boolean exclusiveJit() {
        return exclusiveJit;
    }

    public boolean autoApproveLowRisk() {
        return autoApproveLowRisk;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public Entitlement publish(Instant occurredAt) {
        Instant timestamp = requireLifecycleTimestamp(occurredAt);
        if (requestable) {
            return this;
        }
        return copy(true, timestamp, version);
    }

    public Entitlement unpublish(Instant occurredAt) {
        Instant timestamp = requireLifecycleTimestamp(occurredAt);
        if (!requestable) {
            return this;
        }
        return copy(false, timestamp, version);
    }

    /** Declaration that this entitlement targets a resource reserved for extension-managed JIT access. */
    public Entitlement withExclusiveJit(boolean exclusiveJit, Instant occurredAt) {
        Instant timestamp = requireLifecycleTimestamp(occurredAt);
        if (this.exclusiveJit == exclusiveJit) {
            return this;
        }
        return copy(requestable, exclusiveJit, timestamp, version);
    }

    public Entitlement withAutoApproval(boolean enabled, Instant occurredAt) {
        Instant timestamp = requireLifecycleTimestamp(occurredAt);
        if (enabled && riskLevel != RiskLevel.LOW) {
            throw new IllegalArgumentException("Auto-approval is only available for LOW risk");
        }
        if (autoApproveLowRisk == enabled) {
            return this;
        }
        return copy(requestable, exclusiveJit, enabled, timestamp, version);
    }

    public Entitlement updateDetails(
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            Instant occurredAt) {
        DurationPolicy policy = riskLevel == this.riskLevel
                ? durationPolicy : DurationPolicy.defaultsFor(riskLevel);
        return updateDetails(displayName, description, riskLevel, approverRoleId, policy, occurredAt);
    }

    public Entitlement updateDetails(
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            DurationPolicy durationPolicy,
            Instant occurredAt) {
        Instant timestamp = requireLifecycleTimestamp(occurredAt);
        return new Entitlement(
                id,
                realmId,
                resourceType,
                resourceId,
                displayName,
                description,
                riskLevel,
                approverRoleId,
                durationPolicy,
                requestable,
                exclusiveJit,
                autoApproveLowRisk && riskLevel == RiskLevel.LOW,
                createdAt,
                timestamp,
                version);
    }

    public Entitlement withVersion(long version) {
        return copy(requestable, exclusiveJit, updatedAt, version);
    }

    private Entitlement copy(boolean requestable, Instant updatedAt, long version) {
        return copy(requestable, exclusiveJit, updatedAt, version);
    }

    private Entitlement copy(boolean requestable, boolean exclusiveJit, Instant updatedAt, long version) {
        return copy(requestable, exclusiveJit, autoApproveLowRisk, updatedAt, version);
    }

    private Entitlement copy(boolean requestable, boolean exclusiveJit, boolean autoApproveLowRisk,
            Instant updatedAt, long version) {
        return new Entitlement(
                id,
                realmId,
                resourceType,
                resourceId,
                displayName,
                description,
                riskLevel,
                approverRoleId,
                durationPolicy,
                requestable,
                exclusiveJit,
                autoApproveLowRisk,
                createdAt,
                updatedAt,
                version);
    }

    private Instant requireLifecycleTimestamp(Instant occurredAt) {
        Instant timestamp = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (timestamp.isBefore(createdAt)) {
            throw new IllegalArgumentException("occurredAt must not be before createdAt");
        }
        return timestamp;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
