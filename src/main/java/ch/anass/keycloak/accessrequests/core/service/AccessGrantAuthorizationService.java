package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationAuthority;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;

import java.util.Objects;

/** Authorizes an existing grant only after checking current, locked JIT policy. */
public final class AccessGrantAuthorizationService {

    private final AccessGrantRevocationRepository grants;
    private final EntitlementRepository entitlements;
    private final AccessGrantRevocationAuthority authority;
    private final AccessRequestTransaction transaction;

    public AccessGrantAuthorizationService(AccessGrantRevocationRepository grants,
            EntitlementRepository entitlements, AccessGrantRevocationAuthority authority,
            AccessRequestTransaction transaction) {
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
        this.entitlements = Objects.requireNonNull(entitlements, "entitlements must not be null");
        this.authority = Objects.requireNonNull(authority, "authority must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    public void authorize(String realmId, String requestId) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        transaction.execute(() -> {
            AccessGrant grant = grants.findByRequestIdForUpdate(realmId, requestId).orElse(null);
            if (grant == null) {
                return null;
            }
            if (!realmId.equals(grant.realmId()) || !requestId.equals(grant.requestId())) {
                throw new IllegalStateException("Grant repository returned a grant outside the requested scope");
            }
            Entitlement entitlement = entitlements.findByIdForUpdate(realmId, grant.entitlementId())
                    .orElseThrow(() -> new IllegalStateException("Grant entitlement is unavailable"));
            AccessGrant authorized = grant.authorizeForRevocation(
                    entitlement, authority.isExclusivelyManaged(grant));
            if (authorized != grant) {
                grants.updateIfVersionMatches(authorized, grant.version())
                        .orElseThrow(() -> new ConcurrentGrantModificationException(requestId));
            }
            return null;
        });
    }
}
