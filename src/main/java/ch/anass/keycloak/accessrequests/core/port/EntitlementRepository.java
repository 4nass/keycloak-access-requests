package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;

import java.util.Optional;

public interface EntitlementRepository {

    Optional<Entitlement> findById(String realmId, String entitlementId);

    /**
     * Loads an entitlement while preventing concurrent policy changes until the current transaction completes.
     */
    Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId);

    CatalogPage findRequestable(CatalogQuery query);
}
