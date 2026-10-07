package ch.anass.keycloak.accessrequests.persistence.jpa.entity;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalAssuranceEvidence;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Basic;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;

@Entity
@Table(name = "AR_ACCESS_REQUEST_HISTORY")
public class AccessRequestEventEntity {

    @Id
    @Column(name = "ID", nullable = false, length = 36)
    private String id;

    @Column(name = "REQUEST_ID", nullable = false, length = 36)
    private String requestId;

    @Column(name = "REALM_ID", nullable = false, length = 255)
    private String realmId;

    @Enumerated(EnumType.STRING)
    @Column(name = "EVENT_TYPE", nullable = false, length = 50)
    private AccessRequestEventType type;

    @Column(name = "ACTOR_ID", nullable = false, length = 255)
    private String actorId;

    @Column(name = "EVENT_TIMESTAMP", nullable = false)
    private long occurredAt;

    @Column(name = "REQUEST_VERSION")
    private Long requestVersion;

    @Column(name = "REVOCATION_ATTEMPT")
    private Long revocationAttempt;

    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "COMMENT", columnDefinition = "TEXT")
    private String comment;

    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "METADATA", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "ASSURANCE_REQUIRED_ACR", length = 128)
    private String assuranceRequiredAcr;

    @Column(name = "ASSURANCE_REQUIRED_LOA")
    private Integer assuranceRequiredLoa;

    @Column(name = "ASSURANCE_MAX_AGE_SECONDS")
    private Integer assuranceMaxAgeSeconds;

    @Column(name = "ASSURANCE_OBSERVED_ACR", length = 128)
    private String assuranceObservedAcr;

    @Column(name = "ASSURANCE_OBSERVED_LOA")
    private Integer assuranceObservedLoa;

    @Column(name = "ASSURANCE_AUTHENTICATED_TIMESTAMP")
    private Long assuranceAuthenticatedAt;

    @Column(name = "ASSURANCE_TOKEN_ISSUED_TIMESTAMP")
    private Long assuranceTokenIssuedAt;

    @Column(name = "ASSURANCE_VERIFIED_TIMESTAMP")
    private Long assuranceVerifiedAt;

    protected AccessRequestEventEntity() {
    }

    public AccessRequestEventEntity(AccessRequestEvent event) {
        this.id = event.id();
        this.requestId = event.requestId();
        this.realmId = event.realmId();
        this.type = event.type();
        this.actorId = event.actorId();
        this.occurredAt = event.occurredAt().toEpochMilli();
        this.requestVersion = event.requestVersion();
        this.revocationAttempt = event.revocationAttempt();
        this.comment = event.comment();
        this.metadata = event.metadata();
        ApprovalAssuranceEvidence evidence = event.assuranceEvidence();
        if (evidence != null) {
            this.assuranceRequiredAcr = evidence.requiredAcr();
            this.assuranceRequiredLoa = evidence.requiredLoa();
            this.assuranceMaxAgeSeconds = evidence.maxAgeSeconds();
            this.assuranceObservedAcr = evidence.observedAcr();
            this.assuranceObservedLoa = evidence.observedLoa();
            this.assuranceAuthenticatedAt = evidence.authenticatedAt();
            this.assuranceTokenIssuedAt = evidence.tokenIssuedAt();
            this.assuranceVerifiedAt = evidence.verifiedAt();
        }
    }

    public AccessRequestEvent toDomain() {
        ApprovalAssuranceEvidence evidence = assuranceRequiredAcr == null ? null
                : new ApprovalAssuranceEvidence(assuranceRequiredAcr, assuranceRequiredLoa,
                        assuranceMaxAgeSeconds, assuranceObservedAcr, assuranceObservedLoa,
                        assuranceAuthenticatedAt, assuranceTokenIssuedAt, assuranceVerifiedAt);
        return AccessRequestEvent.rehydrate(
                id,
                requestId,
                realmId,
                type,
                actorId,
                java.time.Instant.ofEpochMilli(occurredAt),
                comment,
                metadata,
                requestVersion,
                revocationAttempt,
                evidence);
    }
}
