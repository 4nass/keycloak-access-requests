package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;

/**
 * Independently verifies current exclusive management before authorization and again before removal.
 * For a package grant, verification must include the persisted package binding and the current
 * delivery group; an AR_PKG_ name or the historical grant origin is not sufficient proof.
 */
@FunctionalInterface
public interface AccessGrantRevocationAuthority {

    boolean isExclusivelyManaged(AccessGrant grant);
}
