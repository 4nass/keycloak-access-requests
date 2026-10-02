package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailure;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
import java.time.Instant;
import java.util.Optional;

public interface AccessGrantRevocationFailureRepository {
    /** Called only in a new transaction after a failed removal transaction has rolled back. */
    Optional<GrantRevocationFailure> record(String realmId, String requestId,
            GrantRevocationFailureCode code, Instant now);

    /** Called in the same transaction as a proven successful removal. */
    void resolve(String realmId, String requestId, Instant now);

    Optional<GrantRevocationFailure> findOpen(String realmId, String requestId);
}
