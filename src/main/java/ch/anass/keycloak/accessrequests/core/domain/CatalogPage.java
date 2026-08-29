package ch.anass.keycloak.accessrequests.core.domain;

import java.util.List;
import java.util.Objects;

public record CatalogPage(List<Entitlement> items, int page, int size, long total) {

    public CatalogPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
    }
}
