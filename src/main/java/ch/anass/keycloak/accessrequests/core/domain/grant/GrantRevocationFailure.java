package ch.anass.keycloak.accessrequests.core.domain.grant;

import java.time.Instant;

/** Operational state of an expired grant whose removal has not yet been proven. */
public record GrantRevocationFailure(String requestId, String realmId, GrantRevocationFailureCode code,
        int attemptCount, Instant firstFailedAt, Instant lastFailedAt, Instant nextAttemptAt, Instant resolvedAt) {
    public boolean isOpen() {
        return resolvedAt == null;
    }
}
