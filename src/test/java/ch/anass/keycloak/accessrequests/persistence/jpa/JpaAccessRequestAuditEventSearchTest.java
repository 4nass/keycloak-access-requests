package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaAccessRequestAuditEventSearchTest {

    private static final Instant FIRST = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-09-02T10:00:00Z");
    private static EntityManagerFactory factory;

    @BeforeAll
    static void startDatabase() {
        factory = Persistence.createEntityManagerFactory("access-requests-test");
    }

    @AfterAll
    static void stopDatabase() {
        factory.close();
    }

    @Test
    void searchesExistingHistoryByRealmWithStableNewestFirstPagination() throws Exception {
        try (EntityManager entityManager = factory.createEntityManager()) {
            persist(entityManager,
                    event("realm-a", "request-1", "actor-a", AccessRequestEventType.REQUEST_CREATED, FIRST, 0),
                    event("realm-a", "request-1", "actor-b", AccessRequestEventType.REQUEST_APPROVED, SECOND, 1),
                    event("realm-a", "request-1", "actor-b", AccessRequestEventType.PROVISIONING_STARTED, SECOND, 1),
                    event("realm-a", "request-1", "actor-b", AccessRequestEventType.PROVISIONING_SUCCEEDED, SECOND, 2),
                    event("realm-a", "request-2", "actor-c", AccessRequestEventType.REQUEST_CANCELED, SECOND, 1),
                    event("realm-b", "request-secret", "actor-secret", AccessRequestEventType.REQUEST_CREATED, SECOND, 0));

            Object firstPage = search(entityManager, "realm-a", null, null, null, null, null, 0, 2);
            Object secondPage = search(entityManager, "realm-a", null, null, null, null, null, 1, 2);
            Object lastPage = search(entityManager, "realm-a", null, null, null, null, null, 2, 2);
            assertEquals(5L, total(firstPage));
            assertEquals(2, items(firstPage).size());
            assertEquals(2, items(secondPage).size());
            assertEquals(1, items(lastPage).size());
            assertEquals(5, java.util.stream.Stream.of(firstPage, secondPage, lastPage)
                    .flatMap(page -> items(page).stream())
                    .map(AccessRequestEvent::id).distinct().count());
            assertEquals(AccessRequestEventType.REQUEST_CREATED, items(lastPage).getFirst().type());
            assertTrue(items(firstPage).stream().allMatch(event -> event.realmId().equals("realm-a")));
            assertTrue(items(secondPage).stream().allMatch(event -> event.realmId().equals("realm-a")));
        }
    }

    @Test
    void combinesInclusiveDateTypeActorAndRequestFilters() throws Exception {
        try (EntityManager entityManager = factory.createEntityManager()) {
            persist(entityManager,
                    event("realm-filter", "request-a", "approver-a", AccessRequestEventType.REQUEST_APPROVED, FIRST, 1),
                    event("realm-filter", "request-a", "approver-a", AccessRequestEventType.REQUEST_APPROVED, SECOND, 1),
                    event("realm-filter", "request-b", "approver-a", AccessRequestEventType.REQUEST_APPROVED, SECOND, 1),
                    event("realm-filter", "request-a", "approver-b", AccessRequestEventType.REQUEST_APPROVED, SECOND, 1),
                    event("realm-filter", "request-a", "approver-a", AccessRequestEventType.REQUEST_REJECTED, SECOND, 1));

            Object page = search(entityManager, "realm-filter", SECOND, SECOND,
                    AccessRequestEventType.REQUEST_APPROVED, "approver-a", "request-a", 0, 20);
            assertEquals(1L, total(page));
            assertEquals(AccessRequestEventType.REQUEST_APPROVED, items(page).getFirst().type());
            assertEquals(SECOND, items(page).getFirst().occurredAt());
        }
    }

    @Test
    void roundsSubMillisecondLowerBoundsUpWithoutChangingExactOrPreEpochBounds() throws Exception {
        Instant beforeEpoch = Instant.parse("1969-12-31T23:59:59.999Z");
        try (EntityManager entityManager = factory.createEntityManager()) {
            persist(entityManager,
                    event("realm-precision", "request-at-boundary", "actor",
                            AccessRequestEventType.REQUEST_CREATED, FIRST, 0),
                    event("realm-precision", "request-after-boundary", "actor",
                            AccessRequestEventType.REQUEST_CREATED, FIRST.plusMillis(1), 0),
                    event("realm-before-epoch", "request-before-epoch", "actor",
                            AccessRequestEventType.REQUEST_CREATED, beforeEpoch, 0),
                    event("realm-before-epoch", "request-at-epoch", "actor",
                            AccessRequestEventType.REQUEST_CREATED, Instant.EPOCH, 0));

            Object fractional = search(entityManager, "realm-precision", FIRST.plusNanos(1),
                    FIRST.plusMillis(1), null, null, null, 0, 20);
            assertEquals(1L, total(fractional));
            assertEquals("request-after-boundary", items(fractional).getFirst().requestId());

            Object exact = search(entityManager, "realm-precision", FIRST,
                    FIRST, null, null, null, 0, 20);
            assertEquals(1L, total(exact));
            assertEquals("request-at-boundary", items(exact).getFirst().requestId());

            Object empty = search(entityManager, "realm-precision", FIRST.plusNanos(1),
                    FIRST.plusNanos(999_999), null, null, null, 0, 20);
            assertEquals(0L, total(empty));

            Object before1970 = search(entityManager, "realm-before-epoch", beforeEpoch.plusNanos(1),
                    Instant.EPOCH, null, null, null, 0, 20);
            assertEquals(1L, total(before1970));
            assertEquals("request-at-epoch", items(before1970).getFirst().requestId());
        }
    }

    @Test
    void rejectsUnboundedOrInvalidAuditQueries() throws Exception {
        try (EntityManager entityManager = factory.createEntityManager()) {
            assertInvalid(entityManager, "realm-a", null, null, null, null, null, -1, 20);
            assertInvalid(entityManager, "realm-a", null, null, null, null, null, 0, 0);
            assertInvalid(entityManager, "realm-a", null, null, null, null, null, 0, 101);
            assertInvalid(entityManager, "realm-a", SECOND, FIRST, null, null, null, 0, 20);
            assertInvalid(entityManager, "realm-a", Instant.MAX, null, null, null, null, 0, 20);
            assertInvalid(entityManager, "realm-a", null, Instant.MIN, null, null, null, 0, 20);
            assertInvalid(entityManager, "realm-a", Instant.ofEpochMilli(Long.MAX_VALUE).plusNanos(1),
                    null, null, null, null, 0, 20);
        }
    }

    @Test
    void pagesRequestHistoryInTheSameChronologicalOrderAsTheExistingReader() {
        try (EntityManager entityManager = factory.createEntityManager()) {
            Instant at = FIRST.plusMillis(10);
            persist(entityManager,
                    event("realm-detail", "request-many", "requester", AccessRequestEventType.REQUEST_CREATED, FIRST, 0),
                    event("realm-detail", "request-many", "approver", AccessRequestEventType.REQUEST_APPROVED, at, 1),
                    event("realm-detail", "request-many", "approver", AccessRequestEventType.PROVISIONING_STARTED, at, 1),
                    event("realm-detail", "request-many", "approver", AccessRequestEventType.PROVISIONING_FAILED, at, 2),
                    event("realm-detail", "request-many", "manager", AccessRequestEventType.PROVISIONING_STARTED, at, 2),
                    event("realm-detail", "request-many", "manager", AccessRequestEventType.PROVISIONING_SUCCEEDED, at, 3),
                    event("other-realm", "request-many", "secret", AccessRequestEventType.REQUEST_CREATED, at, 0));
            JpaAccessRequestHistoryReader reader = new JpaAccessRequestHistoryReader(entityManager);
            List<AccessRequestEvent> expected = reader.findByRequestId("realm-detail", "request-many");
            List<AccessRequestEvent> actual = java.util.stream.IntStream.range(0, 3)
                    .mapToObj(page -> reader.findPageByRequestId("realm-detail", "request-many", page, 2))
                    .peek(result -> assertEquals(6, result.total()))
                    .flatMap(result -> result.items().stream()).toList();
            assertEquals(expected.stream().map(AccessRequestEvent::id).toList(),
                    actual.stream().map(AccessRequestEvent::id).toList());
            assertEquals(0, reader.findPageByRequestId("realm-detail", "request-many", 3, 2).items().size());
            assertThrows(IllegalArgumentException.class,
                    () -> reader.findPageByRequestId("realm-detail", "request-many", 0, 101));
        }
    }

    private static void persist(EntityManager entityManager, AccessRequestEvent... events) {
        entityManager.getTransaction().begin();
        for (AccessRequestEvent event : events) {
            entityManager.persist(new AccessRequestEventEntity(event));
        }
        entityManager.getTransaction().commit();
        entityManager.clear();
    }

    private static AccessRequestEvent event(String realm, String request, String actor,
            AccessRequestEventType type, Instant at, long version) {
        return AccessRequestEvent.rehydrate(UUID.randomUUID().toString(), request, realm, type,
                actor, at, "Sensitive decision comment", "Sensitive failure detail", version);
    }

    private static Object search(EntityManager entityManager, String realm, Instant from, Instant to,
            AccessRequestEventType type, String actor, String request, int page, int size) throws Exception {
        Method method = JpaAccessRequestHistoryReader.class.getMethod("findAll", String.class,
                Instant.class, Instant.class, AccessRequestEventType.class, String.class, String.class,
                int.class, int.class);
        return method.invoke(new JpaAccessRequestHistoryReader(entityManager), realm, from, to,
                type, actor, request, page, size);
    }

    @SuppressWarnings("unchecked")
    private static List<AccessRequestEvent> items(Object page) {
        try {
            return (List<AccessRequestEvent>) page.getClass().getMethod("items").invoke(page);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("The audit search must return a page with items().", exception);
        }
    }

    private static long total(Object page) {
        try {
            return ((Number) page.getClass().getMethod("total").invoke(page)).longValue();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("The audit search must return a page with total().", exception);
        }
    }

    private static void assertInvalid(EntityManager entityManager, String realm, Instant from, Instant to,
            AccessRequestEventType type, String actor, String request, int page, int size) throws Exception {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> search(entityManager, realm, from, to, type, actor, request, page, size));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
    }
}
