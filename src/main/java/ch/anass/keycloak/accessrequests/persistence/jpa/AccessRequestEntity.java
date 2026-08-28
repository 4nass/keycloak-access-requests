package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "AR_ACCESS_REQUEST",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_ACCESS_REQUEST_PENDING",
                columnNames = {"REALM_ID", "REQUESTER_ID", "ENTITLEMENT_ID", "DECISION_STATUS"}))
public class AccessRequestEntity {

    @Id
    @Column(name = "ID", nullable = false, length = 36)
    private String id;

    @Column(name = "REALM_ID", nullable = false, length = 255)
    private String realmId;

    @Column(name = "REQUESTER_ID", nullable = false, length = 255)
    private String requesterId;

    @Column(name = "ENTITLEMENT_ID", nullable = false, length = 36)
    private String entitlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "RESOURCE_TYPE", nullable = false, length = 30)
    private ResourceType resourceType;

    @Column(name = "RESOURCE_ID", nullable = false, length = 255)
    private String resourceId;

    @Column(name = "RESOURCE_NAME_SNAPSHOT", nullable = false, length = 255)
    private String resourceNameSnapshot;

    @Column(name = "JUSTIFICATION", nullable = false, length = 2000)
    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(name = "DECISION_STATUS", nullable = false, length = 30)
    private DecisionStatus decisionStatus;

    @Column(name = "APPROVER_ID", length = 255)
    private String approverId;

    @Column(name = "DECISION_COMMENT", length = 2000)
    private String decisionComment;

    @Version
    @Column(name = "VERSION", nullable = false)
    private long version;

    protected AccessRequestEntity() {
    }

    private AccessRequestEntity(AccessRequest request) {
        apply(request);
    }

    static AccessRequestEntity from(AccessRequest request) {
        return new AccessRequestEntity(request);
    }

    void apply(AccessRequest request) {
        this.id = request.id();
        this.realmId = request.realmId();
        this.requesterId = request.requesterId();
        this.entitlementId = request.entitlementId();
        this.resourceType = request.resourceType();
        this.resourceId = request.resourceId();
        this.resourceNameSnapshot = request.resourceNameSnapshot();
        this.justification = request.justification();
        this.decisionStatus = request.decisionStatus();
        this.approverId = request.approverId();
        this.decisionComment = request.decisionComment();
    }

    AccessRequest toDomain() {
        AccessRequest request = AccessRequest.create(
                id,
                realmId,
                requesterId,
                entitlementId,
                resourceType,
                resourceId,
                resourceNameSnapshot,
                justification);
        if (decisionStatus == DecisionStatus.APPROVED) {
            request.approve(approverId, decisionComment);
        } else if (decisionStatus == DecisionStatus.REJECTED) {
            request.reject(approverId, decisionComment);
        } else if (decisionStatus == DecisionStatus.CANCELED) {
            request.cancel(requesterId);
        }
        return request.withVersion(version);
    }

    String realmId() {
        return realmId;
    }
}
