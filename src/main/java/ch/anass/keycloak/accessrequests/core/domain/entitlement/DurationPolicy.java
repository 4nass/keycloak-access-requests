package ch.anass.keycloak.accessrequests.core.domain.entitlement;

import java.time.Duration;
import java.util.Objects;

/** Configured bounds for the duration selected by a requester. */
public record DurationPolicy(Duration defaultDuration, Duration maxDuration, boolean allowPermanent) {

    public static DurationPolicy defaultsFor(RiskLevel riskLevel) {
        return switch (Objects.requireNonNull(riskLevel, "riskLevel must not be null")) {
            case LOW -> new DurationPolicy(Duration.ofDays(30), Duration.ofDays(90), false);
            case MEDIUM -> new DurationPolicy(Duration.ofDays(7), Duration.ofDays(30), false);
            case HIGH -> new DurationPolicy(Duration.ofHours(8), Duration.ofHours(24), false);
            case CRITICAL -> new DurationPolicy(Duration.ofHours(1), Duration.ofHours(4), false);
        };
    }

    public DurationPolicy {
        Objects.requireNonNull(defaultDuration, "defaultDuration must not be null");
        Objects.requireNonNull(maxDuration, "maxDuration must not be null");
        if (!isPositive(defaultDuration) || !isPositive(maxDuration)
                || defaultDuration.getNano() != 0 || maxDuration.getNano() != 0
                || defaultDuration.compareTo(maxDuration) > 0) {
            throw new IllegalArgumentException(
                    "Durations must be positive whole seconds and defaultDuration must not exceed maxDuration");
        }
    }

    public void validate(Duration requestedDuration, boolean permanent) {
        if (permanent) {
            if (!allowPermanent || requestedDuration != null) {
                throw new IllegalArgumentException("Permanent access is not permitted by this entitlement");
            }
        } else if (requestedDuration == null || !isPositive(requestedDuration)
                || requestedDuration.getNano() != 0
                || requestedDuration.compareTo(maxDuration) > 0) {
            throw new IllegalArgumentException("Requested duration must be positive and within the configured maximum");
        }
    }

    private static boolean isPositive(Duration duration) {
        return !duration.isZero() && !duration.isNegative();
    }
}
