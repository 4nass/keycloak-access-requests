package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.Instant;

@Entity
@Table(
        name = "AR_ENTITLEMENT",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_ENTITLEMENT_RESOURCE",
                columnNames = {"REALM_ID", "RESOURCE_TYPE", "RESOURCE_ID"}))
public class EntitlementEntity {

    @Id
    @Column(name = "ID", nullable = false, length = 36)
    private String id;

    @Column(name = "REALM_ID", nullable = false, length = 255)
    private String realmId;

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

    @Column(name = "CREATED_TIMESTAMP", nullable = false)
    private long createdTimestamp;

    @Column(name = "UPDATED_TIMESTAMP", nullable = false)
    private long updatedTimestamp;

    @Version
    @Column(name = "VERSION", nullable = false)
    private long version;

    protected EntitlementEntity() {
    }

    private EntitlementEntity(Entitlement entitlement) {
        apply(entitlement);
    }

    static EntitlementEntity from(Entitlement entitlement) {
        return new EntitlementEntity(entitlement);
    }

    void apply(Entitlement entitlement) {
        this.id = entitlement.id();
        this.realmId = entitlement.realmId();
        this.resourceType = entitlement.resourceType();
        this.resourceId = entitlement.resourceId();
        this.displayName = entitlement.displayName();
        this.description = entitlement.description();
        this.riskLevel = entitlement.riskLevel();
        this.approverRoleId = entitlement.approverRoleId();
        this.requestable = entitlement.requestable();
        this.createdTimestamp = entitlement.createdAt().toEpochMilli();
        this.updatedTimestamp = entitlement.updatedAt().toEpochMilli();
    }

    Entitlement toDomain() {
        return Entitlement.rehydrate(
                id,
                realmId,
                resourceType,
                resourceId,
                displayName,
                description,
                riskLevel,
                approverRoleId,
                requestable,
                Instant.ofEpochMilli(createdTimestamp),
                Instant.ofEpochMilli(updatedTimestamp),
                version);
    }
}
