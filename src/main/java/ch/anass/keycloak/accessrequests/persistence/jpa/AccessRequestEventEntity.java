package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
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

    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "COMMENT", columnDefinition = "TEXT")
    private String comment;

    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "METADATA", columnDefinition = "TEXT")
    private String metadata;

    protected AccessRequestEventEntity() {
    }

    AccessRequestEventEntity(AccessRequestEvent event) {
        this.id = event.id();
        this.requestId = event.requestId();
        this.realmId = event.realmId();
        this.type = event.type();
        this.actorId = event.actorId();
        this.occurredAt = event.occurredAt().toEpochMilli();
        this.comment = event.comment();
        this.metadata = event.metadata();
    }
}
