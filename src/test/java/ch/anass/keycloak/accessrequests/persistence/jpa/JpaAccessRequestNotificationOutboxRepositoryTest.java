package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaAccessRequestNotificationOutboxRepositoryTest {

    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void createPersistenceUnit() {
        entityManagerFactory = Persistence.createEntityManagerFactory("access-requests-test");
    }

    @AfterEach
    void closePersistenceUnit() {
        entityManagerFactory.close();
    }

    @Test
    void leasesAQueuedDeliveryOnlyOnceAndNeverRequeuesItAfterDelivery() {
        Instant queuedAt = Instant.parse("2026-09-22T10:00:00Z");
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            new JpaAccessRequestNotificationOutboxRepository(entityManager)
                    .enqueue(notification(), AccessRequestNotificationRecipientType.USER, "requester-1", queuedAt);
            entityManager.getTransaction().commit();
        }

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);
            List<AccessRequestNotificationOutboxEntity> claimed = outbox.claimDue(
                    queuedAt, Duration.ofMinutes(5), "processor-1", 50);
            assertEquals(1, claimed.size());
            assertEquals(AccessRequestNotificationOutboxState.PROCESSING, claimed.getFirst().state());
            assertEquals(1, claimed.getFirst().attemptCount());
            assertTrue(outbox.claimDue(queuedAt, Duration.ofMinutes(5), "processor-2", 50).isEmpty());
            outbox.markDelivered(claimed.getFirst().id(), "processor-1", queuedAt);
            entityManager.getTransaction().commit();
        }

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            assertTrue(new JpaAccessRequestNotificationOutboxRepository(entityManager)
                    .claimDue(queuedAt.plus(Duration.ofDays(1)), Duration.ofMinutes(5), "processor-3", 50)
                    .isEmpty());
            entityManager.getTransaction().commit();
        }
    }

    @Test
    void commitsTheLeaseForTheOwnerAndRejectsOtherOrExpiredClaims() {
        Instant queuedAt = Instant.parse("2026-09-22T10:00:00Z");
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            new JpaAccessRequestNotificationOutboxRepository(entityManager)
                    .enqueue(notification(), AccessRequestNotificationRecipientType.USER, "requester-lease", queuedAt);
            entityManager.getTransaction().commit();
        }

        String deliveryId;
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            deliveryId = new JpaAccessRequestNotificationOutboxRepository(entityManager)
                    .claimDue(queuedAt, Duration.ofMinutes(5), "processor-lease", 1)
                    .getFirst()
                    .id();
            entityManager.getTransaction().commit();
        }

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);
            assertTrue(outbox.ownsActiveClaim(deliveryId, "processor-lease", queuedAt.plusSeconds(1)));
            assertFalse(outbox.ownsActiveClaim(deliveryId, "another-processor", queuedAt.plusSeconds(1)));
            assertFalse(outbox.ownsActiveClaim(deliveryId, "processor-lease", queuedAt.plus(Duration.ofMinutes(6))));
            entityManager.getTransaction().commit();
        }
    }

    @Test
    void retriesAFailedDeliveryWithTheSameDurableDeliveryKey() {
        Instant queuedAt = Instant.parse("2026-09-22T10:00:00Z");
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            new JpaAccessRequestNotificationOutboxRepository(entityManager)
                    .enqueue(notification(), AccessRequestNotificationRecipientType.USER, "requester-1", queuedAt);
            entityManager.getTransaction().commit();
        }

        String deliveryKey;
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);
            AccessRequestNotificationOutboxEntity firstAttempt = outbox
                    .claimDue(queuedAt, Duration.ofMinutes(5), "processor-1", 50)
                    .getFirst();
            deliveryKey = firstAttempt.deliveryKey();
            outbox.markFailed(firstAttempt.id(), "processor-1", queuedAt, 10);
            entityManager.getTransaction().commit();
        }

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            AccessRequestNotificationOutboxEntity secondAttempt = new JpaAccessRequestNotificationOutboxRepository(entityManager)
                    .claimDue(queuedAt.plusSeconds(3), Duration.ofMinutes(5), "processor-2", 50)
                    .getFirst();
            assertEquals(deliveryKey, secondAttempt.deliveryKey());
            assertEquals(2, secondAttempt.attemptCount());
            entityManager.getTransaction().commit();
        }
    }

    @Test
    void doesNotQueueTheSameExpandedRoleDeliveryTwice() {
        Instant queuedAt = Instant.parse("2026-09-22T10:00:00Z");
        AccessRequestNotification notification = notification();
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);
            outbox.enqueueIfAbsent(
                    notification,
                    AccessRequestNotificationRecipientType.USER,
                    List.of("approver-1"),
                    queuedAt);
            entityManager.getTransaction().commit();

            entityManager.getTransaction().begin();
            outbox.enqueueIfAbsent(
                    notification,
                    AccessRequestNotificationRecipientType.USER,
                    List.of("approver-1"),
                    queuedAt);
            entityManager.getTransaction().commit();

            Long queued = entityManager.createQuery(
                            "select count(entry) from AccessRequestNotificationOutboxEntity entry",
                            Long.class)
                    .getSingleResult();
            assertEquals(1L, queued);
        }
    }

    @Test
    void listsAndSummarizesOnlyFailedDeliveriesInTheRequestedRealm() {
        Instant queuedAt = Instant.parse("2026-09-22T10:00:00Z");
        String failedDeliveryId;
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);
            outbox.enqueue(notification(), AccessRequestNotificationRecipientType.USER, "failed-recipient", queuedAt);
            outbox.enqueue(notification(), AccessRequestNotificationRecipientType.USER, "pending-recipient", queuedAt);
            AccessRequestNotificationOutboxEntity failed = outbox.claimDue(
                    queuedAt, Duration.ofMinutes(5), "processor-1", 1).getFirst();
            failedDeliveryId = failed.id();
            outbox.markFailed(failedDeliveryId, "processor-1", queuedAt.plusSeconds(1), 1);
            entityManager.getTransaction().commit();
        }

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);

            JpaAccessRequestNotificationOutboxRepository.NotificationOutboxPage failed =
                    outbox.findFailed("realm-1", 0, 20);
            assertEquals(List.of(failedDeliveryId), failed.items().stream()
                    .map(AccessRequestNotificationOutboxEntity::id)
                    .toList());
            assertEquals(1, failed.total());
            assertEquals(queuedAt, failed.items().getFirst().lastAttemptAt());

            assertEquals(
                    new JpaAccessRequestNotificationOutboxRepository.NotificationOutboxSummary(1, 0, 0, 0, 1),
                    outbox.summarize("realm-1"));
            entityManager.getTransaction().commit();
        }
    }

    @Test
    void requeuesOnlyTerminalFailuresWithANewRetryBudget() {
        Instant queuedAt = Instant.parse("2026-09-22T10:00:00Z");
        String failedDeliveryId;
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);
            outbox.enqueue(notification(), AccessRequestNotificationRecipientType.USER, "requester-1", queuedAt);
            AccessRequestNotificationOutboxEntity failed = outbox.claimDue(
                    queuedAt, Duration.ofMinutes(5), "processor-1", 1).getFirst();
            failedDeliveryId = failed.id();
            outbox.markFailed(failedDeliveryId, "processor-1", queuedAt, 1);
            entityManager.getTransaction().commit();
        }

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);
            assertEquals(
                    JpaAccessRequestNotificationOutboxRepository.RetryFailedResult.RETRIED,
                    outbox.retryFailed("realm-1", failedDeliveryId, queuedAt.plusSeconds(10)));
            assertTrue(outbox.findFailed("realm-1", 0, 20).items().isEmpty());
            AccessRequestNotificationOutboxEntity retried = outbox.claimDue(
                    queuedAt.plusSeconds(10), Duration.ofMinutes(5), "processor-2", 1).getFirst();
            assertEquals(1, retried.attemptCount());
            assertEquals(
                    JpaAccessRequestNotificationOutboxRepository.RetryFailedResult.NOT_FAILED,
                    outbox.retryFailed("realm-1", failedDeliveryId, queuedAt.plusSeconds(11)));
            assertEquals(
                    JpaAccessRequestNotificationOutboxRepository.RetryFailedResult.NOT_FOUND,
                    outbox.retryFailed("another-realm", failedDeliveryId, queuedAt.plusSeconds(11)));
            entityManager.getTransaction().commit();
        }
    }

    private static AccessRequestNotification notification() {
        Instant now = Instant.parse("2026-09-22T10:00:00Z");
        Entitlement entitlement = Entitlement.create(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "managed-role",
                "Finance reader",
                "Read access to finance data.",
                RiskLevel.HIGH,
                "approver-role",
                now);
        AccessRequest request = AccessRequest.create(
                "request-1",
                "realm-1",
                "requester-1",
                entitlement.id(),
                entitlement.resourceType(),
                entitlement.resourceId(),
                entitlement.displayName(),
                "Need read access for month-end close.",
                now);
        return new AccessRequestNotification(
                AccessRequestNotificationType.REQUEST_SUBMITTED,
                AccessRequestNotificationRecipientType.USER,
                "requester-1",
                request,
                entitlement,
                AccessRequestEvent.created(request, request.requesterId(), now));
    }
}
