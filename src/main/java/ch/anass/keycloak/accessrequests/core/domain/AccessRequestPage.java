package ch.anass.keycloak.accessrequests.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * A page of access requests belonging to one requester.
 */
public record AccessRequestPage(List<AccessRequest> items, int page, int size, long total) {

    public AccessRequestPage {
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
