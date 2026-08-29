package ch.anass.keycloak.accessrequests.core.domain;

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
