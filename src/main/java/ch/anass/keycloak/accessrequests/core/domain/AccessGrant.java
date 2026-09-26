package ch.anass.keycloak.accessrequests.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Provenance of a successful provisioning attempt. PREEXISTING access is not owned by this extension.
 */
public record AccessGrant(
        String requestId,
        String realmId,
        String requesterId,
        String entitlementId,
        ResourceType resourceType,
        String resourceId,
        GrantOrigin origin,
        Instant recordedAt) {

    public AccessGrant {
        requestId = requireText(requestId, "requestId");
        realmId = requireText(realmId, "realmId");
        requesterId = requireText(requesterId, "requesterId");
        entitlementId = requireText(entitlementId, "entitlementId");
        resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        resourceId = requireText(resourceId, "resourceId");
        origin = Objects.requireNonNull(origin, "origin must not be null");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
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

    public boolean ownedByExtension() {
        return origin == GrantOrigin.CREATED_BY_EXTENSION;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
