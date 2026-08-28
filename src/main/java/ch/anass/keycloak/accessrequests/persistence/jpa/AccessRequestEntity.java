package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;

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

    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "JUSTIFICATION", nullable = false, columnDefinition = "TEXT")
    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(name = "DECISION_STATUS", nullable = false, length = 30)
    private DecisionStatus decisionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "PROVISIONING_STATUS", nullable = false, length = 30)
    private ProvisioningStatus provisioningStatus;

    @Column(name = "APPROVER_ID", length = 255)
    private String approverId;

    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "DECISION_COMMENT", columnDefinition = "TEXT")
    private String decisionComment;

    @Column(name = "CREATED_TIMESTAMP", nullable = false)
    private long createdTimestamp;

    @Column(name = "UPDATED_TIMESTAMP", nullable = false)
    private long updatedTimestamp;

    @Column(name = "DECIDED_TIMESTAMP")
    private Long decidedTimestamp;

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
        this.provisioningStatus = request.provisioningStatus();
        this.approverId = request.approverId();
        this.decisionComment = request.decisionComment();
        this.createdTimestamp = request.createdAt().toEpochMilli();
        this.updatedTimestamp = request.updatedAt().toEpochMilli();
        this.decidedTimestamp = request.decidedAt() == null ? null : request.decidedAt().toEpochMilli();
    }

    AccessRequest toDomain() {
        return AccessRequest.rehydrate(
                id,
                realmId,
                requesterId,
                entitlementId,
                resourceType,
                resourceId,
                resourceNameSnapshot,
                justification,
                decisionStatus,
                provisioningStatus,
                approverId,
                decisionComment,
                java.time.Instant.ofEpochMilli(createdTimestamp),
                java.time.Instant.ofEpochMilli(updatedTimestamp),
                decidedTimestamp == null ? null : java.time.Instant.ofEpochMilli(decidedTimestamp),
                version);
    }

    String realmId() {
        return realmId;
    }
}
