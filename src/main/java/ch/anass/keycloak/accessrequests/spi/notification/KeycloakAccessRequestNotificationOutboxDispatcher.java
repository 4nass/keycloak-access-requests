package ch.anass.keycloak.accessrequests.spi.notification;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.persistence.jpa.AccessRequestEventEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.AccessRequestNotificationOutboxEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestNotificationOutboxRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaEntitlementRepository;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.timer.ScheduledTask;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Delivers persisted notifications from a background Keycloak timer after their source transaction commits.
 */
public final class KeycloakAccessRequestNotificationOutboxDispatcher implements ScheduledTask {

    public static final String TASK_NAME = "access-requests-notification-outbox";
    private static final Logger LOG = Logger.getLogger(KeycloakAccessRequestNotificationOutboxDispatcher.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 50;
    private static final int MAXIMUM_ATTEMPTS = 10;

    @Override
    public String getTaskName() {
        return TASK_NAME;
    }

    @Override
    public void run(KeycloakSession session) {
        EntityManager entityManager = Objects.requireNonNull(
                session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null")
                .getEntityManager();
        JpaAccessRequestNotificationOutboxRepository outbox =
                new JpaAccessRequestNotificationOutboxRepository(entityManager);
        Instant now = Instant.now();
        String processorId = UUID.randomUUID().toString();
        List<AccessRequestNotificationOutboxEntity> entries =
                outbox.claimDue(now, LEASE_DURATION, processorId, BATCH_SIZE);
        for (AccessRequestNotificationOutboxEntity entry : entries) {
            deliver(session, entityManager, outbox, entry, processorId, now);
        }
    }

    private static void deliver(
            KeycloakSession session,
            EntityManager entityManager,
            JpaAccessRequestNotificationOutboxRepository outbox,
            AccessRequestNotificationOutboxEntity entry,
            String processorId,
            Instant now) {
        try {
            RealmModel realm = session.realms().getRealm(entry.realmId());
            AccessRequest request = new JpaAccessRequestRepository(entityManager)
                    .findById(entry.realmId(), entry.requestId())
                    .orElse(null);
            Entitlement entitlement = new JpaEntitlementRepository(entityManager)
                    .findById(entry.realmId(), entry.entitlementId())
                    .orElse(null);
            AccessRequestEventEntity eventEntity = entityManager.find(AccessRequestEventEntity.class, entry.eventId());
            if (realm == null || request == null || entitlement == null || eventEntity == null) {
                outbox.markDiscarded(entry.id(), processorId, now);
                return;
            }
            session.getContext().setRealm(realm);
            AccessRequestNotification notification = new AccessRequestNotification(
                    entry.notificationType(),
                    AccessRequestNotificationRecipientType.USER,
                    entry.recipientId(),
                    request,
                    entitlement,
                    eventEntity.toDomain());
            KeycloakAccessRequestEmailNotifier.DeliveryResult result =
                    new KeycloakAccessRequestEmailNotifier(session, realm).deliver(notification, entry.recipientId());
            if (result == KeycloakAccessRequestEmailNotifier.DeliveryResult.SENT) {
                outbox.markDelivered(entry.id(), processorId, now);
            } else {
                outbox.markDiscarded(entry.id(), processorId, now);
            }
        } catch (Exception exception) {
            LOG.warnf(
                    exception,
                    "Could not deliver access request notification %s for request %s.",
                    entry.notificationType(),
                    entry.requestId());
            outbox.markFailed(entry.id(), processorId, now, MAXIMUM_ATTEMPTS);
        }
    }
}
