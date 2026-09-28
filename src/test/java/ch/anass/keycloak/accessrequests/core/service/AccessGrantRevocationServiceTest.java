package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationAuthority;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevoker;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Core contract: checking an expiry must never remove a mapping without verified JIT authority. */
class AccessGrantRevocationServiceTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-09-01T14:00:00Z");
    private static final Clock AT_EXPIRY = Clock.fixed(EXPIRES_AT, ZoneOffset.UTC);

    @Test
    void removesAnExpiredOwnedJitMappingOnceAndRecordsRevocationOnlyAfterRemoval() {
        Fixture fixture = fixture(grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.AUTHORIZED), true, AT_EXPIRY);
        fixture.revoker = current -> {
            assertEquals(GrantRevocationState.AUTHORIZED, fixture.repository.current().revocationState());
            fixture.removals.incrementAndGet();
        };

        fixture.service().revokeExpired("realm-1", "request-1");
        fixture.service().revokeExpired("realm-1", "request-1");

        assertEquals(1, fixture.removals.get());
        assertEquals(GrantRevocationState.REVOKED, fixture.repository.current().revocationState());
        assertEquals(1, fixture.repository.current().version());
    }

    @Test
    void leavesUnexpiredPermanentPreexistingUnverifiedAndInvalidatedMappingsUntouched() {
        AccessGrant[] ineligible = {
                grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT.plusSeconds(1), GrantRevocationState.AUTHORIZED),
                grant(GrantOrigin.CREATED_BY_EXTENSION, null, GrantRevocationState.AUTHORIZED),
                grant(GrantOrigin.PREEXISTING, null, GrantRevocationState.UNVERIFIED),
                grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT, GrantRevocationState.UNVERIFIED),
                grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT, GrantRevocationState.INVALIDATED)
        };

        for (AccessGrant grant : ineligible) {
            Fixture fixture = fixture(grant, true, AT_EXPIRY);
            fixture.service().revokeExpired("realm-1", "request-1");
            assertEquals(0, fixture.removals.get(), grant.revocationState().name());
            assertEquals(grant, fixture.repository.current());
        }
    }

    @Test
    void refusesRemovalWhenExclusiveManagementOfTheJitResourceIsNotVerified() {
        Fixture fixture = fixture(grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.AUTHORIZED), false, AT_EXPIRY);

        fixture.service().revokeExpired("realm-1", "request-1");

        assertEquals(0, fixture.removals.get());
        assertEquals(GrantRevocationState.AUTHORIZED, fixture.repository.current().revocationState());
    }

    @Test
    void aRemovalFailureLeavesTheGrantRetryableAndDoesNotPretendItWasRevoked() {
        Fixture fixture = fixture(grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.AUTHORIZED), true, AT_EXPIRY);
        fixture.revoker = current -> {
            if (fixture.removals.incrementAndGet() == 1) {
                throw new IllegalStateException("Temporary provider failure");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> fixture.service().revokeExpired("realm-1", "request-1"));
        assertEquals(GrantRevocationState.AUTHORIZED, fixture.repository.current().revocationState());

        fixture.service().revokeExpired("realm-1", "request-1");
        assertEquals(2, fixture.removals.get());
        assertEquals(GrantRevocationState.REVOKED, fixture.repository.current().revocationState());
    }

    @Test
    void invalidationWinsBeforeAStaleWorkerCanRemoveTheMapping() {
        Fixture fixture = fixture(grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.AUTHORIZED), true, AT_EXPIRY);
        assertTrue(fixture.repository.invalidateIfVersionMatches("realm-1", "request-1", 0).isPresent());

        fixture.service().revokeExpired("realm-1", "request-1");

        assertEquals(0, fixture.removals.get());
        assertEquals(GrantRevocationState.INVALIDATED, fixture.repository.current().revocationState());
        assertEquals(1, fixture.repository.current().version());
    }

    @Test
    void simultaneousWorkersCannotRemoveTheSameMappingTwice() throws Exception {
        Fixture fixture = fixture(grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.AUTHORIZED), true, AT_EXPIRY);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> revokeAfter(start, fixture));
            var second = workers.submit(() -> revokeAfter(start, fixture));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, fixture.removals.get());
        assertEquals(GrantRevocationState.REVOKED, fixture.repository.current().revocationState());
    }

    @Test
    void aCrossRealmLookupCannotTriggerRemoval() {
        Fixture fixture = fixture(grant(GrantOrigin.CREATED_BY_EXTENSION, EXPIRES_AT,
                GrantRevocationState.AUTHORIZED), true, AT_EXPIRY);

        fixture.service().revokeExpired("other-realm", "request-1");

        assertEquals(0, fixture.removals.get());
        assertEquals(GrantRevocationState.AUTHORIZED, fixture.repository.current().revocationState());
    }

    private static void revokeAfter(CountDownLatch start, Fixture fixture) {
        try {
            start.await();
            fixture.service().revokeExpired("realm-1", "request-1");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static Fixture fixture(AccessGrant grant, boolean exclusiveJit, Clock clock) {
        return new Fixture(new InMemoryGrantRepository(grant), exclusiveJit, clock);
    }

    private static AccessGrant grant(GrantOrigin origin, Instant expiresAt, GrantRevocationState state) {
        return new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1", ResourceType.REALM_ROLE,
                "jit-role-1", origin, EXPIRES_AT.minus(Duration.ofHours(4)), expiresAt, state, 0);
    }

    private static final class Fixture {
        private final InMemoryGrantRepository repository;
        private final boolean exclusiveJit;
        private final Clock clock;
        private final AtomicInteger removals = new AtomicInteger();
        private AccessGrantRevoker revoker = current -> removals.incrementAndGet();

        private Fixture(InMemoryGrantRepository repository, boolean exclusiveJit, Clock clock) {
            this.repository = repository;
            this.exclusiveJit = exclusiveJit;
            this.clock = clock;
        }

        private AccessGrantRevocationService service() {
            AccessGrantRevocationAuthority authority = current -> exclusiveJit;
            AccessRequestTransaction transaction = new AccessRequestTransaction() {
                @Override
                public <T> T execute(Supplier<T> operation) {
                    synchronized (repository) {
                        return operation.get();
                    }
                }
            };
            return new AccessGrantRevocationService(repository, authority, revoker, transaction, clock);
        }
    }

    private static final class InMemoryGrantRepository implements AccessGrantRepository {
        private AccessGrant current;

        private InMemoryGrantRepository(AccessGrant grant) {
            current = grant;
        }

        @Override
        public void create(AccessGrant grant) {
            throw new UnsupportedOperationException("This test starts with a persisted grant");
        }

        @Override
        public Optional<AccessGrant> findByRequestId(String realmId, String requestId) {
            return current.realmId().equals(realmId) && current.requestId().equals(requestId)
                    ? Optional.of(current) : Optional.empty();
        }

        public Optional<AccessGrant> findByRequestIdForUpdate(String realmId, String requestId) {
            return findByRequestId(realmId, requestId);
        }

        @Override
        public Optional<AccessGrant> invalidateIfVersionMatches(String realmId, String requestId, long expectedVersion) {
            if (findByRequestId(realmId, requestId).isEmpty() || current.version() != expectedVersion
                    || current.revocationState() == GrantRevocationState.REVOKED) {
                return Optional.empty();
            }
            current = withVersion(current.invalidate(), expectedVersion + 1);
            return Optional.of(current);
        }

        public Optional<AccessGrant> updateIfVersionMatches(AccessGrant updated, long expectedVersion) {
            if (!current.realmId().equals(updated.realmId()) || !current.requestId().equals(updated.requestId())
                    || current.version() != expectedVersion) {
                return Optional.empty();
            }
            current = withVersion(updated, expectedVersion + 1);
            return Optional.of(current);
        }

        private AccessGrant current() {
            return current;
        }

        private static AccessGrant withVersion(AccessGrant grant, long version) {
            return new AccessGrant(grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                    grant.resourceType(), grant.resourceId(), grant.origin(), grant.recordedAt(), grant.expiresAt(),
                    grant.revocationState(), version);
        }
    }
}
