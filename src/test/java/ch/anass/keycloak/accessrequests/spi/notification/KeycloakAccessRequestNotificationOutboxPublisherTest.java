package ch.anass.keycloak.accessrequests.spi.notification;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.persistence.jpa.AccessRequestNotificationOutboxEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KeycloakAccessRequestNotificationOutboxPublisherTest {

    private static final String REALM_ID = "realm-1";

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
    void queuesDeliveriesInTheCurrentTransactionWithoutResolvingRoleMembers() {
        AtomicBoolean roleLookupRequested = new AtomicBoolean();
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            publisher(entityManager, roleLookupRequested)
                    .publish(notification(AccessRequestNotificationRecipientType.USER, "requester-1"));

            assertFalse(roleLookupRequested.get());
            entityManager.getTransaction().rollback();

            Long queued = entityManager.createQuery(
                            "select count(entry) from AccessRequestNotificationOutboxEntity entry", Long.class)
                    .getSingleResult();
            assertEquals(0L, queued);
        }
    }

    @Test
    void queuesOneRoleInstructionWithoutResolvingItsMembers() {
        AtomicBoolean roleLookupRequested = new AtomicBoolean();
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            publisher(entityManager, roleLookupRequested)
                    .publish(notification(AccessRequestNotificationRecipientType.REALM_ROLE, "approver-role"));
            entityManager.getTransaction().commit();

            List<AccessRequestNotificationOutboxEntity> entries = entityManager.createQuery(
                            "select entry from AccessRequestNotificationOutboxEntity entry",
                            AccessRequestNotificationOutboxEntity.class)
                    .getResultList();
            assertEquals(1, entries.size());
            assertEquals("approver-role", entries.getFirst().recipientId());
            assertEquals(AccessRequestNotificationRecipientType.REALM_ROLE, entries.getFirst().recipientType());
            assertFalse(roleLookupRequested.get());
        }
    }

    private KeycloakAccessRequestNotificationOutboxPublisher publisher(
            EntityManager entityManager,
            AtomicBoolean roleLookupRequested) {
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> REALM_ID;
            case "getRoleById" -> {
                roleLookupRequested.set(true);
                yield null;
            }
            default -> null;
        });
        return new KeycloakAccessRequestNotificationOutboxPublisher(realm, entityManager);
    }

    private static AccessRequestNotification notification(
            AccessRequestNotificationRecipientType recipientType,
            String recipientId) {
        Instant now = Instant.parse("2026-09-22T10:00:00Z");
        Entitlement entitlement = Entitlement.create(
                "entitlement-1",
                REALM_ID,
                ResourceType.REALM_ROLE,
                "managed-role",
                "Finance reader",
                "Read access to finance data.",
                RiskLevel.HIGH,
                "approver-role",
                now);
        AccessRequest request = AccessRequest.create(
                "request-1",
                REALM_ID,
                "requester-1",
                entitlement.id(),
                entitlement.resourceType(),
                entitlement.resourceId(),
                entitlement.displayName(),
                "Need read access for month-end close.",
                now);
        return new AccessRequestNotification(
                AccessRequestNotificationType.REQUEST_SUBMITTED,
                recipientType,
                recipientId,
                request,
                entitlement,
                AccessRequestEvent.created(request, request.requesterId(), now));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
