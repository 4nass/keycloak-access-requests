package ch.anass.keycloak.accessrequests.core.domain.grant;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.DurationPolicy;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
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
        Instant expiresAt,
        GrantRevocationState revocationState,
        long version) {

    public AccessGrant(String requestId, String realmId, String requesterId, String entitlementId,
            ResourceType resourceType, String resourceId, GrantOrigin origin, Instant recordedAt) {
        this(requestId, realmId, requesterId, entitlementId, resourceType, resourceId, origin, recordedAt,
                null, GrantRevocationState.UNVERIFIED, 0);
    }

    public AccessGrant(String requestId, String realmId, String requesterId, String entitlementId,
            ResourceType resourceType, String resourceId, GrantOrigin origin, Instant recordedAt,
            GrantRevocationState revocationState, long version) {
        this(requestId, realmId, requesterId, entitlementId, resourceType, resourceId, origin, recordedAt,
                null, revocationState, version);
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
        if (expiresAt != null && !expiresAt.isAfter(recordedAt)) {
            throw new IllegalArgumentException("expiresAt must be after recordedAt");
        }
        if (origin == GrantOrigin.PREEXISTING && expiresAt != null) {
            throw new IllegalArgumentException("Preexisting access must not have an extension-owned expiry");
        }
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

    public static AccessGrant from(
            AccessRequest request, Entitlement entitlement, GrantOrigin origin, Instant recordedAt) {
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
        Instant expiresAt = null;
        // A legacy request without a selected duration cannot acquire an invented expiry here.
        if (origin == GrantOrigin.CREATED_BY_EXTENSION && !request.permanent()
                && request.requestedDurationSeconds() != null) {
            expiresAt = DurationPolicy.expiryAt(recordedAt, request.requestedDurationSeconds());
        }
        return new AccessGrant(request.id(), request.realmId(), request.requesterId(), entitlement.id(),
                entitlement.resourceType(), entitlement.resourceId(), origin, recordedAt, expiresAt,
                GrantRevocationState.UNVERIFIED, 0);
    }

    /**
     * Only an independently verified exclusive-management policy may set AUTHORIZED.
     * New grants deliberately start UNVERIFIED, even when this extension created the mapping.
     */
    public boolean canAutoRevoke() {
        return origin == GrantOrigin.CREATED_BY_EXTENSION
                && expiresAt != null
                && revocationState == GrantRevocationState.AUTHORIZED;
    }

    public boolean isDueAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return origin == GrantOrigin.CREATED_BY_EXTENSION
                && expiresAt != null
                && !now.isBefore(expiresAt);
    }

    public boolean canAutoRevokeAt(Instant now) {
        return canAutoRevoke() && isDueAt(now);
    }

    /**
     * Authorizes expiry only when current entitlement policy and an independent check agree.
     * The caller must obtain that check while holding the grant and entitlement locks.
     */
    public AccessGrant authorizeForRevocation(Entitlement entitlement, boolean exclusivelyManaged) {
        Objects.requireNonNull(entitlement, "entitlement must not be null");
        if (!realmId.equals(entitlement.realmId()) || !entitlementId.equals(entitlement.id())
                || resourceType != entitlement.resourceType() || !resourceId.equals(entitlement.resourceId())) {
            throw new IllegalArgumentException("Grant and entitlement must identify the same resource");
        }
        if (origin != GrantOrigin.CREATED_BY_EXTENSION || expiresAt == null
                || revocationState == GrantRevocationState.INVALIDATED
                || revocationState == GrantRevocationState.REVOKED
                || !entitlement.exclusiveJit() || !exclusivelyManaged) {
            throw new IllegalStateException("Exclusive JIT revocation authority has not been verified");
        }
        return revocationState == GrantRevocationState.AUTHORIZED
                ? this : withState(GrantRevocationState.AUTHORIZED);
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
                origin, recordedAt, expiresAt, state, version);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
