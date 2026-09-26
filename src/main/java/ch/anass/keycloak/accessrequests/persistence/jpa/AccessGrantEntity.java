package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "AR_ACCESS_GRANT")
public class AccessGrantEntity {

    @Id
    @Column(name = "REQUEST_ID", nullable = false, length = 36)
    private String requestId;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "GRANT_ORIGIN", nullable = false, length = 30)
    private GrantOrigin origin;

    @Column(name = "RECORDED_TIMESTAMP", nullable = false)
    private long recordedTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "REVOCATION_STATE", nullable = false, length = 30)
    private GrantRevocationState revocationState;

    @Version
    @Column(name = "VERSION", nullable = false)
    private long version;

    protected AccessGrantEntity() {
    }

    private AccessGrantEntity(AccessGrant grant) {
        requestId = grant.requestId();
        realmId = grant.realmId();
        requesterId = grant.requesterId();
        entitlementId = grant.entitlementId();
        resourceType = grant.resourceType();
        resourceId = grant.resourceId();
        origin = grant.origin();
        recordedTimestamp = grant.recordedAt().toEpochMilli();
        revocationState = grant.revocationState();
        version = grant.version();
    }

    static AccessGrantEntity from(AccessGrant grant) {
        return new AccessGrantEntity(grant);
    }

    AccessGrant toDomain() {
        return new AccessGrant(requestId, realmId, requesterId, entitlementId, resourceType, resourceId,
                origin, Instant.ofEpochMilli(recordedTimestamp), revocationState, version);
    }

    String realmId() {
        return realmId;
    }
}
