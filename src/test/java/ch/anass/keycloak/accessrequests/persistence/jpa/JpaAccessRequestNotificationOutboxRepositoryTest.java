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
                    .enqueue(notification(), "requester-1", queuedAt);
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
    void retriesAFailedDeliveryWithTheSameDurableDeliveryKey() {
        Instant queuedAt = Instant.parse("2026-09-22T10:00:00Z");
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            new JpaAccessRequestNotificationOutboxRepository(entityManager)
                    .enqueue(notification(), "requester-1", queuedAt);
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
