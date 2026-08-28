package ch.anass.keycloak.accessrequests.core.domain;

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
    private long version;

    private AccessRequest(
            String id,
            String realmId,
            String requesterId,
            String entitlementId,
            String justification,
            ResourceType resourceType,
            String resourceId,
            String resourceNameSnapshot) {
        this.id = requireText(id, "id");
        this.realmId = requireText(realmId, "realmId");
        this.requesterId = requireText(requesterId, "requesterId");
        this.entitlementId = requireText(entitlementId, "entitlementId");
        this.justification = requireText(justification, "justification");
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceNameSnapshot = resourceNameSnapshot;
        this.decisionStatus = DecisionStatus.PENDING;
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
        return new AccessRequest(
                id,
                realmId,
                requesterId,
                entitlementId,
                justification,
                Objects.requireNonNull(resourceType, "resourceType must not be null"),
                requireText(resourceId, "resourceId"),
                requireText(resourceNameSnapshot, "resourceNameSnapshot"));
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

    public long version() {
        return version;
    }

    public AccessRequest copy() {
        return copyWithVersion(version);
    }

    public AccessRequest withVersion(long newVersion) {
        if (newVersion < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        return copyWithVersion(newVersion);
    }

    public void approve(String approverId, String decisionComment) {
        ensurePending();
        this.approverId = requireText(approverId, "approverId");
        this.decisionComment = decisionComment;
        this.decisionStatus = DecisionStatus.APPROVED;
    }

    public void reject(String approverId, String decisionComment) {
        ensurePending();
        this.approverId = requireText(approverId, "approverId");
        this.decisionComment = decisionComment;
        this.decisionStatus = DecisionStatus.REJECTED;
    }

    public void cancel(String actorId) {
        ensurePending();
        String validatedActorId = requireText(actorId, "actorId");
        if (!requesterId.equals(validatedActorId)) {
            throw new UnauthorizedRequestActionException(
                    "Only the request owner can cancel this request.");
        }
        this.decisionStatus = DecisionStatus.CANCELED;
    }

    private void ensurePending() {
        if (decisionStatus != DecisionStatus.PENDING) {
            throw new InvalidRequestStateException(decisionStatus);
        }
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
                resourceNameSnapshot);
        copy.decisionStatus = decisionStatus;
        copy.approverId = approverId;
        copy.decisionComment = decisionComment;
        copy.version = newVersion;
        return copy;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
