package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;

/** Read-only check of the grant's recorded delivery group, independent of the current package binding. */
@FunctionalInterface
public interface AccessGrantMembershipInspector {
    Membership membership(AccessGrant grant);

    enum Membership { PRESENT, ABSENT, UNVERIFIABLE }
}
