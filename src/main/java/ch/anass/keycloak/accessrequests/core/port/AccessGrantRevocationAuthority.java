package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;

/** Independently verifies that the target is an exclusively managed JIT resource. */
@FunctionalInterface
public interface AccessGrantRevocationAuthority {

    boolean isExclusivelyManaged(AccessGrant grant);
}
