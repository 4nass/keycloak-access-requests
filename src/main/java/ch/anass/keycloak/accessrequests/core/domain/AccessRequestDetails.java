package ch.anass.keycloak.accessrequests.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * Immutable requester-visible view of an access request and its audit history.
 */
public record AccessRequestDetails(AccessRequest request, List<AccessRequestEvent> history) {

    public AccessRequestDetails {
        Objects.requireNonNull(request, "request must not be null");
        history = List.copyOf(Objects.requireNonNull(history, "history must not be null"));
    }
}
