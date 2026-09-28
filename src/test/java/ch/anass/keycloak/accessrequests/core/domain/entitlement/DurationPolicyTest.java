package ch.anass.keycloak.accessrequests.core.domain.entitlement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationPolicyTest {

    @ParameterizedTest
    @MethodSource("initialPolicies")
    void riskDefaultsHaveAConservativeMaximumAndNeverAllowPermanentAccess(
            RiskLevel risk, Duration expectedDefault, Duration expectedMaximum) {
        DurationPolicy policy = DurationPolicy.defaultsFor(risk);

        assertEquals(expectedDefault, policy.defaultDuration());
        assertEquals(expectedMaximum, policy.maxDuration());
        assertFalse(policy.allowPermanent());
        assertDoesNotThrow(() -> policy.validate(expectedMaximum, false));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(expectedMaximum.plusSeconds(1), false));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(null, true));
    }

    @Test
    void riskDefaultsRequireARiskLevel() {
        assertThrows(NullPointerException.class, () -> DurationPolicy.defaultsFor(null));
    }

    @Test
    void finiteDurationMustBePositiveAndWithinTheConfiguredMaximum() {
        DurationPolicy policy = new DurationPolicy(Duration.ofDays(7), Duration.ofDays(30), false);

        assertEquals(Duration.ofDays(7), policy.defaultDuration());
        assertEquals(Duration.ofDays(30), policy.maxDuration());
        assertDoesNotThrow(() -> policy.validate(Duration.ofDays(30), false));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Duration.ZERO, false));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Duration.ofSeconds(-1), false));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Duration.ofDays(31), false));
    }

    @Test
    void permanentAccessRequiresAnExplicitOptIn() {
        DurationPolicy finiteOnly = new DurationPolicy(Duration.ofHours(8), Duration.ofDays(1), false);
        DurationPolicy optionalPermanent = new DurationPolicy(Duration.ofHours(8), Duration.ofDays(1), true);

        assertFalse(finiteOnly.allowPermanent());
        assertTrue(optionalPermanent.allowPermanent());
        assertThrows(IllegalArgumentException.class, () -> finiteOnly.validate(null, true));
        assertDoesNotThrow(() -> optionalPermanent.validate(null, true));
        assertThrows(IllegalArgumentException.class, () -> optionalPermanent.validate(Duration.ofHours(1), true));
        assertThrows(IllegalArgumentException.class, () -> finiteOnly.validate(null, false));
    }

    @Test
    void configurationRequiresPositiveOrderedDurations() {
        assertThrows(NullPointerException.class, () -> new DurationPolicy(null, Duration.ofDays(1), false));
        assertThrows(NullPointerException.class, () -> new DurationPolicy(Duration.ofHours(1), null, false));
        assertThrows(IllegalArgumentException.class, () -> new DurationPolicy(Duration.ZERO, Duration.ofDays(1), false));
        assertThrows(IllegalArgumentException.class, () -> new DurationPolicy(Duration.ofHours(1), Duration.ZERO, false));
        assertThrows(IllegalArgumentException.class, () -> new DurationPolicy(Duration.ofDays(2), Duration.ofDays(1), false));
        assertThrows(IllegalArgumentException.class,
                () -> new DurationPolicy(Duration.ofMillis(500), Duration.ofDays(1), false));
        assertThrows(IllegalArgumentException.class,
                () -> new DurationPolicy(Duration.ofHours(1), Duration.ofMillis(500), false));
    }

    @Test
    void rejectsAnUnstorableMaximumAndChecksThePersistedExpiryBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> new DurationPolicy(Duration.ofDays(1), Duration.ofSeconds(Long.MAX_VALUE), false));

        Instant lastPersistableInstant = Instant.ofEpochMilli(Long.MAX_VALUE);
        assertEquals(lastPersistableInstant,
                DurationPolicy.expiryAt(lastPersistableInstant.minusSeconds(1), 1));
        assertThrows(IllegalArgumentException.class,
                () -> DurationPolicy.expiryAt(lastPersistableInstant.minusSeconds(1), 2));
    }

    private static Stream<Arguments> initialPolicies() {
        return Stream.of(
                Arguments.of(RiskLevel.LOW, Duration.ofDays(30), Duration.ofDays(90)),
                Arguments.of(RiskLevel.MEDIUM, Duration.ofDays(7), Duration.ofDays(30)),
                Arguments.of(RiskLevel.HIGH, Duration.ofHours(8), Duration.ofHours(24)),
                Arguments.of(RiskLevel.CRITICAL, Duration.ofHours(1), Duration.ofHours(4)));
    }
}
