package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;

import java.util.Optional;
import java.util.Set;

public interface EntitlementRepository {

    Optional<Entitlement> findById(String realmId, String entitlementId);

    /**
     * Loads an entitlement while preventing concurrent policy changes until the current transaction completes.
     */
    Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId);

    CatalogPage findRequestable(CatalogQuery query);

    /**
     * Returns whether at least one requestable entitlement can be approved by one of the supplied realm roles.
     * Implementations that do not support this read must fail closed.
     */
    default boolean hasRequestableEntitlementForApproverRoles(String realmId, Set<String> approverRoleIds) {
        return false;
    }
}
