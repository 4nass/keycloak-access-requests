package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;

import java.util.Optional;

public interface AccessGrantRepository {

    void create(AccessGrant grant);

    Optional<AccessGrant> findByRequestId(String realmId, String requestId);

    Optional<AccessGrant> invalidateIfVersionMatches(String realmId, String requestId, long expectedVersion);
}
