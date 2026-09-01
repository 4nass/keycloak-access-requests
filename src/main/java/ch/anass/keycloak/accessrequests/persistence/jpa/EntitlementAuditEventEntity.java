package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.EntitlementAuditEvent;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementAuditEventType;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;

@Entity
@Table(name = "AR_ENTITLEMENT_HISTORY")
public class EntitlementAuditEventEntity {

    @Id
    @Column(name = "ID", nullable = false, length = 36)
    private String id;

    @Column(name = "ENTITLEMENT_ID", nullable = false, length = 36)
    private String entitlementId;

    @Column(name = "REALM_ID", nullable = false, length = 255)
    private String realmId;

    @Enumerated(EnumType.STRING)
    @Column(name = "EVENT_TYPE", nullable = false, length = 50)
    private EntitlementAuditEventType type;

    @Column(name = "ACTOR_ID", nullable = false, length = 255)
    private String actorId;

    @Column(name = "EVENT_TIMESTAMP", nullable = false)
    private long occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "RESOURCE_TYPE", nullable = false, length = 30)
    private ResourceType resourceType;

    @Column(name = "RESOURCE_ID", nullable = false, length = 255)
    private String resourceId;

    @Column(name = "DISPLAY_NAME", nullable = false, length = 255)
    private String displayName;

    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "DESCRIPTION", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "RISK_LEVEL", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @Column(name = "APPROVER_ROLE_ID", nullable = false, length = 255)
    private String approverRoleId;

    @Column(name = "REQUESTABLE", nullable = false)
    private boolean requestable;

    @Column(name = "VERSION", nullable = false)
    private long version;

    protected EntitlementAuditEventEntity() {
    }

    EntitlementAuditEventEntity(EntitlementAuditEvent event) {
        this.id = event.id();
        this.entitlementId = event.entitlementId();
        this.realmId = event.realmId();
        this.type = event.type();
        this.actorId = event.actorId();
        this.occurredAt = event.occurredAt().toEpochMilli();
        this.resourceType = event.resourceType();
        this.resourceId = event.resourceId();
        this.displayName = event.displayName();
        this.description = event.description();
        this.riskLevel = event.riskLevel();
        this.approverRoleId = event.approverRoleId();
        this.requestable = event.requestable();
        this.version = event.version();
    }
}
