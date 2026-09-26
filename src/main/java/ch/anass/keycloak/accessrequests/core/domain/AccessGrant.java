package ch.anass.keycloak.accessrequests.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Provenance of a successful provisioning attempt. Origin alone never authorizes revocation.
 */
public record AccessGrant(
        String requestId,
        String realmId,
        String requesterId,
        String entitlementId,
        ResourceType resourceType,
        String resourceId,
        GrantOrigin origin,
        Instant recordedAt,
        GrantRevocationState revocationState,
        long version) {

    public AccessGrant(String requestId, String realmId, String requesterId, String entitlementId,
            ResourceType resourceType, String resourceId, GrantOrigin origin, Instant recordedAt) {
        this(requestId, realmId, requesterId, entitlementId, resourceType, resourceId, origin, recordedAt,
                GrantRevocationState.UNVERIFIED, 0);
    }

    public AccessGrant {
        requestId = requireText(requestId, "requestId");
        realmId = requireText(realmId, "realmId");
        requesterId = requireText(requesterId, "requesterId");
        entitlementId = requireText(entitlementId, "entitlementId");
        resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        resourceId = requireText(resourceId, "resourceId");
        origin = Objects.requireNonNull(origin, "origin must not be null");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        revocationState = Objects.requireNonNull(revocationState, "revocationState must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (origin == GrantOrigin.PREEXISTING
                && (revocationState == GrantRevocationState.AUTHORIZED
                || revocationState == GrantRevocationState.REVOKED)) {
            throw new IllegalArgumentException("Preexisting access cannot be authorized for revocation");
        }
    }

    public static AccessGrant from(AccessRequest request, Entitlement entitlement, GrantOrigin origin, Instant recordedAt) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        if (request.decisionStatus() != DecisionStatus.APPROVED
                || request.provisioningStatus() != ProvisioningStatus.SUCCEEDED) {
            throw new IllegalArgumentException("A grant requires successfully provisioned approval");
        }
        if (!request.realmId().equals(entitlement.realmId())
                || !request.entitlementId().equals(entitlement.id())
                || request.resourceType() != entitlement.resourceType()
                || !request.resourceId().equals(entitlement.resourceId())) {
            throw new IllegalArgumentException("The request and entitlement must identify the same resource");
        }
        return new AccessGrant(request.id(), request.realmId(), request.requesterId(), entitlement.id(),
                entitlement.resourceType(), entitlement.resourceId(), origin, recordedAt);
    }

    /**
     * Only an independently verified exclusive-management policy may set AUTHORIZED.
     * New grants deliberately start UNVERIFIED, even when this extension created the mapping.
     */
    public boolean canAutoRevoke() {
        return origin == GrantOrigin.CREATED_BY_EXTENSION
                && revocationState == GrantRevocationState.AUTHORIZED;
    }

    public AccessGrant invalidate() {
        if (revocationState == GrantRevocationState.REVOKED) {
            throw new IllegalStateException("A revoked grant cannot be invalidated");
        }
        return withState(GrantRevocationState.INVALIDATED);
    }

    public AccessGrant markRevoked() {
        if (!canAutoRevoke()) {
            throw new IllegalStateException("Revocation requires independently verified authority");
        }
        return withState(GrantRevocationState.REVOKED);
    }

    private AccessGrant withState(GrantRevocationState state) {
        return new AccessGrant(requestId, realmId, requesterId, entitlementId, resourceType, resourceId,
                origin, recordedAt, state, version);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
