package io.github.keycloakaccessrequests.core.domain;

import java.util.Objects;

public final class AccessRequest {

    private final String id;
    private final String realmId;
    private final String requesterId;
    private final String entitlementId;
    private final String justification;

    private DecisionStatus decisionStatus;
    private String approverId;
    private String decisionComment;

    private AccessRequest(
            String id,
            String realmId,
            String requesterId,
            String entitlementId,
            String justification) {
        this.id = requireText(id, "id");
        this.realmId = requireText(realmId, "realmId");
        this.requesterId = requireText(requesterId, "requesterId");
        this.entitlementId = requireText(entitlementId, "entitlementId");
        this.justification = requireText(justification, "justification");
        this.decisionStatus = DecisionStatus.PENDING;
    }

    public static AccessRequest create(
            String id,
            String realmId,
            String requesterId,
            String entitlementId,
            String justification) {
        return new AccessRequest(id, realmId, requesterId, entitlementId, justification);
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

    public DecisionStatus decisionStatus() {
        return decisionStatus;
    }

    public String approverId() {
        return approverId;
    }

    public String decisionComment() {
        return decisionComment;
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
        requireText(actorId, "actorId");
        this.decisionStatus = DecisionStatus.CANCELED;
    }

    private void ensurePending() {
        if (decisionStatus != DecisionStatus.PENDING) {
            throw new InvalidRequestStateException(decisionStatus);
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
