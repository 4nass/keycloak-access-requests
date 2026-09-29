package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;

/**
 * Removes a mapping idempotently. The adapter must participate in the same transaction as the grant update;
 * otherwise a failed update could leave the mapping removed while the grant remains retryable.
 */
@FunctionalInterface
public interface AccessGrantRevoker {

    void revoke(AccessGrant grant);
}
