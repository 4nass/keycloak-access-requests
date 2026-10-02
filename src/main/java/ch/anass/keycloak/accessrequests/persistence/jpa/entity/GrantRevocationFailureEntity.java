package ch.anass.keycloak.accessrequests.persistence.jpa.entity;

import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailure;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "AR_GRANT_REVOCATION_FAILURE")
public class GrantRevocationFailureEntity {

    @Id
    @Column(name = "REQUEST_ID", nullable = false, length = 36)
    private String requestId;

    @Column(name = "REALM_ID", nullable = false, length = 255)
    private String realmId;

    @Enumerated(EnumType.STRING)
    @Column(name = "FAILURE_CODE", nullable = false, length = 40)
    private GrantRevocationFailureCode code;

    @Column(name = "ATTEMPT_COUNT", nullable = false)
    private int attemptCount;

    @Column(name = "FIRST_FAILED_TIMESTAMP", nullable = false)
    private long firstFailedTimestamp;

    @Column(name = "LAST_FAILED_TIMESTAMP", nullable = false)
    private long lastFailedTimestamp;

    @Column(name = "NEXT_ATTEMPT_TIMESTAMP", nullable = false)
    private long nextAttemptTimestamp;

    @Column(name = "RESOLVED_TIMESTAMP")
    private Long resolvedTimestamp;

    protected GrantRevocationFailureEntity() {
    }

    public GrantRevocationFailureEntity(String realmId, String requestId,
            GrantRevocationFailureCode code, Instant now) {
        this.realmId = realmId;
        this.requestId = requestId;
        this.firstFailedTimestamp = now.toEpochMilli();
        record(code, now);
    }

    public void record(GrantRevocationFailureCode code, Instant now) {
        this.code = code;
        attemptCount = Math.addExact(attemptCount, 1);
        lastFailedTimestamp = now.toEpochMilli();
        long delay = Math.min(3600, 300L << Math.min(attemptCount - 1, 4));
        nextAttemptTimestamp = now.plusSeconds(delay).toEpochMilli();
        resolvedTimestamp = null;
    }

    public void resolve(Instant now) {
        resolvedTimestamp = now.toEpochMilli();
    }

    public String realmId() {
        return realmId;
    }

    public GrantRevocationFailure toDomain() {
        return new GrantRevocationFailure(requestId, realmId, code, attemptCount,
                Instant.ofEpochMilli(firstFailedTimestamp), Instant.ofEpochMilli(lastFailedTimestamp),
                Instant.ofEpochMilli(nextAttemptTimestamp),
                resolvedTimestamp == null ? null : Instant.ofEpochMilli(resolvedTimestamp));
    }
}
