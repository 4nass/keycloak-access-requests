package ch.anass.keycloak.accessrequests.core.domain.catalog;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import java.util.Objects;

/**
 * A requestable entitlement together with the current user's request availability.
 */
public record CatalogEntry(
        Entitlement entitlement,
        boolean alreadyGranted,
        boolean pendingRequest) {

    public CatalogEntry {
        entitlement = Objects.requireNonNull(entitlement, "entitlement must not be null");
    }
}
