package ch.anass.keycloak.accessrequests.core.domain;

import java.time.Instant;
import java.util.Objects;

public final class AccessRequest {

    private final String id;
    private final String realmId;
    private final String requesterId;
    private final String entitlementId;
    private final String justification;
    private final ResourceType resourceType;
    private final String resourceId;
    private final String resourceNameSnapshot;

    private DecisionStatus decisionStatus;
    private String approverId;
    private String decisionComment;
    private ProvisioningStatus provisioningStatus;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant decidedAt;
    private long version;

    private AccessRequest(
            String id,
            String realmId,
            String requesterId,
            String entitlementId,
            String justification,
            ResourceType resourceType,
            String resourceId,
            String resourceNameSnapshot,
            Instant createdAt) {
        this.id = requireText(id, "id");
        this.realmId = requireText(realmId, "realmId");
        this.requesterId = requireText(requesterId, "requesterId");
        this.entitlementId = requireText(entitlementId, "entitlementId");
        this.justification = requireText(justification, "justification");
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceNameSnapshot = resourceNameSnapshot;
        this.decisionStatus = DecisionStatus.PENDING;
        this.provisioningStatus = ProvisioningStatus.NOT_STARTED;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = createdAt;
    }

    public static AccessRequest create(
            String id,
            String realmId,
            String requesterId,
            String entitlementId,
            ResourceType resourceType,
            String resourceId,
            String resourceNameSnapshot,
            String justification) {
        return create(
                id,
                realmId,
                requesterId,
                entitlementId,
                resourceType,
                resourceId,
                resourceNameSnapshot,
                justification,
                Instant.now());
    }

    public static AccessRequest create(
            String id,
            String realmId,
            String requesterId,
            String entitlementId,
            ResourceType resourceType,
            String resourceId,
            String resourceNameSnapshot,
            String justification,
            Instant createdAt) {
        return new AccessRequest(
                id,
                realmId,
                requesterId,
                entitlementId,
                justification,
                Objects.requireNonNull(resourceType, "resourceType must not be null"),
                requireText(resourceId, "resourceId"),
                requireText(resourceNameSnapshot, "resourceNameSnapshot"),
                createdAt);
    }

    public static AccessRequest rehydrate(
            String id,
            String realmId,
            String requesterId,
            String entitlementId,
            ResourceType resourceType,
            String resourceId,
            String resourceNameSnapshot,
            String justification,
            DecisionStatus decisionStatus,
            ProvisioningStatus provisioningStatus,
            String approverId,
            String decisionComment,
            Instant createdAt,
            Instant updatedAt,
            Instant decidedAt,
            long version) {
        AccessRequest request = create(
                id,
                realmId,
                requesterId,
                entitlementId,
                resourceType,
                resourceId,
                resourceNameSnapshot,
                justification,
                createdAt);
        request.decisionStatus = Objects.requireNonNull(decisionStatus, "decisionStatus must not be null");
        request.provisioningStatus = Objects.requireNonNull(provisioningStatus, "provisioningStatus must not be null");
        request.approverId = approverId;
        request.decisionComment = decisionComment;
        request.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        request.decidedAt = decidedAt;
        request.version = requireNonNegativeVersion(version);
        return request;
    }

    public String id() {
        return id;
    }

    public String realmId() {
        return realmId;
    }

    public String requesterId() {
        return requesterId;
    }

    public String entitlementId() {
        return entitlementId;
    }

    public String justification() {
        return justification;
    }

    public ResourceType resourceType() {
        return resourceType;
    }

    public String resourceId() {
        return resourceId;
    }

    public String resourceNameSnapshot() {
        return resourceNameSnapshot;
    }

    public DecisionStatus decisionStatus() {
        return decisionStatus;
    }

    public String approverId() {
        return approverId;
    }

    public String decisionComment() {
        return decisionComment;
    }

    public ProvisioningStatus provisioningStatus() {
        return provisioningStatus;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public long version() {
        return version;
    }

    public AccessRequest copy() {
        return copyWithVersion(version);
    }

    public AccessRequest withVersion(long newVersion) {
        return copyWithVersion(requireNonNegativeVersion(newVersion));
    }

    public void approve(String approverId, String decisionComment) {
        approve(approverId, decisionComment, Instant.now());
    }

    public void approve(String approverId, String decisionComment, Instant decidedAt) {
        ensurePending();
        this.approverId = requireText(approverId, "approverId");
        this.decisionComment = decisionComment;
        this.decisionStatus = DecisionStatus.APPROVED;
        recordDecision(decidedAt);
    }

    public void markProvisioningSucceeded(Instant completedAt) {
        completeProvisioning(ProvisioningStatus.SUCCEEDED, completedAt);
    }

    public void markProvisioningFailed(Instant completedAt) {
        completeProvisioning(ProvisioningStatus.FAILED, completedAt);
    }

    public void reject(String approverId, String decisionComment) {
        reject(approverId, decisionComment, Instant.now());
    }

    public void reject(String approverId, String decisionComment, Instant decidedAt) {
        ensurePending();
        this.approverId = requireText(approverId, "approverId");
        this.decisionComment = decisionComment;
        this.decisionStatus = DecisionStatus.REJECTED;
        recordDecision(decidedAt);
    }

    public void cancel(String actorId) {
        cancel(actorId, Instant.now());
    }

    public void cancel(String actorId, Instant decidedAt) {
        ensurePending();
        String validatedActorId = requireText(actorId, "actorId");
        if (!requesterId.equals(validatedActorId)) {
            throw new UnauthorizedRequestActionException(
                    "Only the request owner can cancel this request.");
        }
        this.decisionStatus = DecisionStatus.CANCELED;
        recordDecision(decidedAt);
    }

    private void ensurePending() {
        if (decisionStatus != DecisionStatus.PENDING) {
            throw new InvalidRequestStateException(decisionStatus);
        }
    }

    private void completeProvisioning(ProvisioningStatus result, Instant completedAt) {
        if (decisionStatus != DecisionStatus.APPROVED || provisioningStatus != ProvisioningStatus.NOT_STARTED) {
            throw new InvalidRequestStateException(decisionStatus);
        }
        this.provisioningStatus = Objects.requireNonNull(result, "result must not be null");
        this.updatedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    private AccessRequest copyWithVersion(long newVersion) {
        AccessRequest copy = new AccessRequest(
                id,
                realmId,
                requesterId,
                entitlementId,
                justification,
                resourceType,
                resourceId,
                resourceNameSnapshot,
                createdAt);
        copy.decisionStatus = decisionStatus;
        copy.approverId = approverId;
        copy.decisionComment = decisionComment;
        copy.provisioningStatus = provisioningStatus;
        copy.updatedAt = updatedAt;
        copy.decidedAt = decidedAt;
        copy.version = newVersion;
        return copy;
    }

    private void recordDecision(Instant decisionTime) {
        this.decidedAt = Objects.requireNonNull(decisionTime, "decidedAt must not be null");
        this.updatedAt = decisionTime;
    }

    private static long requireNonNegativeVersion(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
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
