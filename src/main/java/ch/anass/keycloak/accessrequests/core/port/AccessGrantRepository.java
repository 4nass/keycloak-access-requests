package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.AccessGrant;

import java.util.Optional;

public interface AccessGrantRepository {

    void create(AccessGrant grant);

    Optional<AccessGrant> findByRequestId(String realmId, String requestId);
}
