package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import ch.anass.keycloak.accessrequests.core.service.AccessGrantRevocationService;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPackageGrantExpirationDispatcherTest {

    private static final Instant DUE_AT = Instant.parse("2026-10-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(DUE_AT, ZoneOffset.UTC);

    @Test
    void doesNotStartARevocationWhenNoGrantIsDue() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AccessPackageGrantExpirationDispatcher dispatcher = new AccessPackageGrantExpirationDispatcher(
                CLOCK, (factory, dueAt, afterExpiry, afterRequestId, limit) -> {
                    reads.incrementAndGet();
                    return List.of();
                }, (factory, realmId, requestId) -> attempts.incrementAndGet());

        dispatcher.run(session(sessionFactory()));

        assertEquals(1, reads.get());
        assertEquals(0, attempts.get());
    }

    @Test
    void scansStablePagesAndInvokesIsolatedRevocationByRealmAndRequestId() {
        List<AccessGrant> candidates = grants(51);
        List<String> revoked = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        KeycloakSessionFactory factory = sessionFactory();
        AccessPackageGrantExpirationDispatcher dispatcher = new AccessPackageGrantExpirationDispatcher(
                CLOCK, (actualFactory, dueAt, afterExpiry, afterRequestId, limit) -> {
                    assertSame(factory, actualFactory);
                    assertEquals(DUE_AT, dueAt);
                    assertEquals(50, limit);
                    cursors.add(afterRequestId);
                    return page(candidates, afterExpiry, afterRequestId, limit);
                }, (actualFactory, realmId, requestId) -> {
                    assertSame(factory, actualFactory);
                    revoked.add(realmId + "/" + requestId);
                });

        dispatcher.run(session(factory));

        assertEquals("access-requests-package-grant-expiration", dispatcher.getTaskName());
        assertEquals(List.of("realm-1/grant-000", "realm-2/grant-001"), revoked.subList(0, 2));
        assertEquals("realm-1/grant-050", revoked.getLast());
        assertEquals(51, revoked.size());
        assertEquals(java.util.Arrays.asList(null, "grant-049"), cursors);
    }

    @Test
    void oneFailedRevocationDoesNotAbortOtherGrantsAndIsRetriedOnTheNextTick() {
        List<AccessGrant> candidates = grants(51);
        Set<String> completed = ConcurrentHashMap.newKeySet();
        AtomicInteger failedAttempts = new AtomicInteger();
        AccessPackageGrantExpirationDispatcher dispatcher = new AccessPackageGrantExpirationDispatcher(
                CLOCK, (factory, dueAt, afterExpiry, afterRequestId, limit) ->
                        page(candidates.stream().filter(grant -> !completed.contains(grant.requestId())).toList(),
                                afterExpiry, afterRequestId, limit),
                (factory, realmId, requestId) -> {
                    if (requestId.equals("grant-000")) {
                        failedAttempts.incrementAndGet();
                        throw new IllegalStateException("Simulated transient group removal failure");
                    }
                    completed.add(requestId);
                });

        dispatcher.run(session(sessionFactory()));
        assertEquals(1, failedAttempts.get());
        assertEquals(50, completed.size(), "Later pages must run despite one failed grant");
        assertTrue(completed.contains("grant-050"));

        dispatcher.run(session(sessionFactory()));
        assertEquals(2, failedAttempts.get(), "A failed grant must remain eligible for a later tick");
        assertEquals(50, completed.size());
    }

    @Test
    void boundsOneTickAndContinuesFromItsCursorOnTheNextTick() {
        List<AccessGrant> candidates = grants(103);
        List<String> attempted = new ArrayList<>();
        AccessPackageGrantExpirationDispatcher dispatcher = new AccessPackageGrantExpirationDispatcher(
                CLOCK, (factory, dueAt, afterExpiry, afterRequestId, limit) ->
                        page(candidates, afterExpiry, afterRequestId, limit),
                (factory, realmId, requestId) -> attempted.add(requestId));
        KeycloakSession session = session(sessionFactory());

        dispatcher.run(session);
        assertEquals(100, attempted.size(), "A timer tick must not monopolize a Keycloak worker");
        assertEquals("grant-099", attempted.getLast());

        dispatcher.run(session);
        assertEquals(103, attempted.size());
        assertEquals("grant-100", attempted.get(100));
        assertEquals("grant-102", attempted.getLast());
    }

    @Test
    void retriesAnExpiredFailureEvenWhenTheNormalScanNeverReachesItsEnd() {
        List<AccessGrant> candidates = grants(300);
        AccessGrant failed = candidates.getFirst();
        Set<String> completed = ConcurrentHashMap.newKeySet();
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger failedAttempts = new AtomicInteger();
        List<String> attempted = new ArrayList<>();
        AccessPackageGrantExpirationDispatcher dispatcher = new AccessPackageGrantExpirationDispatcher(
                CLOCK,
                (factory, dueAt, afterExpiry, afterRequestId, limit) ->
                        page(candidates.stream().filter(grant -> !grant.requestId().equals(failed.requestId())
                                && !completed.contains(grant.requestId())).toList(),
                                afterExpiry, afterRequestId, limit),
                (factory, dueAt, limit) -> ticks.incrementAndGet() < 2 ? List.of() : List.of(failed),
                (factory, realmId, requestId) -> {
                    attempted.add(requestId);
                    if (requestId.equals(failed.requestId())) {
                        failedAttempts.incrementAndGet();
                    } else {
                        completed.add(requestId);
                    }
                });

        dispatcher.run(session(sessionFactory()));
        assertEquals(100, attempted.size());
        assertEquals(0, failedAttempts.get());

        dispatcher.run(session(sessionFactory()));
        assertEquals(1, failedAttempts.get(), "An eligible retry must not wait for cursor wraparound");
        assertEquals(200, attempted.size(), "Retries and fresh expirations share the tick budget");
        assertEquals(199, completed.size());
    }

    @Test
    void twoNodesMaySeeTheSameCandidateButOnlyTheLockAwareAttemptRemovesIt() throws Exception {
        AccessGrant candidate = grants(1).getFirst();
        ReentrantLock transactionLock = new ReentrantLock();
        AccessGrant[] current = {candidate};
        AtomicInteger removals = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AccessGrantRevocationRepository repository = new AccessGrantRevocationRepository() {
            @Override
            public void create(AccessGrant grant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<AccessGrant> findDuePackageGrants(
                    Instant dueAt, Instant afterExpiry, String afterRequestId, int limit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<AccessGrant> findByRequestId(String realmId, String requestId) {
                return current[0].realmId().equals(realmId) && current[0].requestId().equals(requestId)
                        ? Optional.of(current[0]) : Optional.empty();
            }

            @Override
            public Optional<AccessGrant> findByRequestIdForUpdate(String realmId, String requestId) {
                return findByRequestId(realmId, requestId);
            }

            @Override
            public Optional<AccessGrant> invalidateIfVersionMatches(
                    String realmId, String requestId, long expectedVersion) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<AccessGrant> updateIfVersionMatches(AccessGrant updated, long expectedVersion) {
                if (current[0].version() != expectedVersion
                        || current[0].revocationState() != GrantRevocationState.AUTHORIZED) {
                    return Optional.empty();
                }
                current[0] = new AccessGrant(updated.requestId(), updated.realmId(), updated.requesterId(),
                        updated.entitlementId(), updated.resourceType(), updated.resourceId(), updated.origin(),
                        updated.recordedAt(), updated.expiresAt(), updated.revocationState(), expectedVersion + 1,
                        updated.deliveryGroupId());
                return Optional.of(current[0]);
            }
        };
        AccessRequestTransaction transaction = new AccessRequestTransaction() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> operation) {
                transactionLock.lock();
                try {
                    return operation.get();
                } finally {
                    transactionLock.unlock();
                }
            }
        };
        AccessGrantRevocationService service = new AccessGrantRevocationService(
                repository, grant -> true, grant -> removals.incrementAndGet(), transaction, CLOCK);
        AccessPackageGrantExpirationDispatcher.DueGrantPageReader pages =
                (factory, dueAt, afterExpiry, afterRequestId, limit) ->
                        afterRequestId == null ? List.of(candidate) : List.of();
        AccessPackageGrantExpirationDispatcher.RevocationAttempt revocation = (factory, realmId, requestId) -> {
            attempts.incrementAndGet();
            service.revokeExpired(realmId, requestId);
        };
        AccessPackageGrantExpirationDispatcher first = new AccessPackageGrantExpirationDispatcher(
                CLOCK, pages, revocation);
        AccessPackageGrantExpirationDispatcher second = new AccessPackageGrantExpirationDispatcher(
                CLOCK, pages, revocation);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstRun = workers.submit(() -> {
                await(start);
                first.run(session(sessionFactory()));
            });
            var secondRun = workers.submit(() -> {
                await(start);
                second.run(session(sessionFactory()));
            });
            start.countDown();
            firstRun.get(10, TimeUnit.SECONDS);
            secondRun.get(10, TimeUnit.SECONDS);
        }

        assertEquals(2, attempts.get(), "The worker must rely on the locked revocation transaction for idempotence");
        assertEquals(1, removals.get());
        assertEquals(GrantRevocationState.REVOKED, current[0].revocationState());
    }

    private static List<AccessGrant> grants(int count) {
        return IntStream.range(0, count).mapToObj(index -> new AccessGrant(
                "grant-%03d".formatted(index), index % 2 == 0 ? "realm-1" : "realm-2",
                "requester-1", "entitlement-1", ResourceType.REALM_ROLE, "source-role",
                GrantOrigin.CREATED_BY_EXTENSION, DUE_AT.minusSeconds(3600), DUE_AT,
                GrantRevocationState.AUTHORIZED, 1, "package-group-1")).toList();
    }

    private static List<AccessGrant> page(List<AccessGrant> grants, Instant afterExpiry,
            String afterRequestId, int limit) {
        return grants.stream()
                .filter(grant -> afterExpiry == null || grant.expiresAt().isAfter(afterExpiry)
                        || (grant.expiresAt().equals(afterExpiry)
                        && grant.requestId().compareTo(afterRequestId) > 0))
                .limit(limit)
                .toList();
    }

    private static KeycloakSessionFactory sessionFactory() {
        return proxy(KeycloakSessionFactory.class, (proxy, method, arguments) -> null);
    }

    private static KeycloakSession session(KeycloakSessionFactory factory) {
        return proxy(KeycloakSession.class, (proxy, method, arguments) ->
                method.getName().equals("getKeycloakSessionFactory") ? factory : null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
