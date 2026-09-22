package ch.anass.keycloak.accessrequests.spi.notification;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
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
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.timer.ScheduledTask;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Delivers persisted notifications from a background Keycloak timer after their source transaction commits.
 */
public final class KeycloakAccessRequestNotificationOutboxDispatcher implements ScheduledTask {

    public static final String TASK_NAME = "access-requests-notification-outbox";
    private static final Logger LOG = Logger.getLogger(KeycloakAccessRequestNotificationOutboxDispatcher.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 50;
    private static final int MAXIMUM_ATTEMPTS = 10;

    private final IsolatedDeliveryRunner isolatedDeliveryRunner;

    public KeycloakAccessRequestNotificationOutboxDispatcher() {
        this(KeycloakAccessRequestNotificationOutboxDispatcher::deliverNext);
    }

    KeycloakAccessRequestNotificationOutboxDispatcher(IsolatedDeliveryRunner isolatedDeliveryRunner) {
        this.isolatedDeliveryRunner = Objects.requireNonNull(
                isolatedDeliveryRunner,
                "isolatedDeliveryRunner must not be null");
    }

    @Override
    public String getTaskName() {
        return TASK_NAME;
    }

    @Override
    public void run(KeycloakSession session) {
        KeycloakSessionFactory sessionFactory = Objects.requireNonNull(
                session,
                "session must not be null")
                .getKeycloakSessionFactory();
        for (int processed = 0; processed < BATCH_SIZE; processed++) {
            if (!isolatedDeliveryRunner.run(sessionFactory)) {
                return;
            }
        }
    }

    private static boolean deliverNext(KeycloakSessionFactory sessionFactory) {
        DeliveryClaim claim = KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory, session -> {
            EntityManager entityManager = Objects.requireNonNull(
                    session.getProvider(JpaConnectionProvider.class),
                    "Keycloak JPA connection provider must not be null")
                    .getEntityManager();
            JpaAccessRequestNotificationOutboxRepository outbox =
                    new JpaAccessRequestNotificationOutboxRepository(entityManager);
            Instant now = Instant.now();
            String processorId = UUID.randomUUID().toString();
            List<AccessRequestNotificationOutboxEntity> entries =
                    outbox.claimDue(now, LEASE_DURATION, processorId, 1);
            if (entries.isEmpty()) {
                return null;
            }
            return DeliveryClaim.from(entries.getFirst(), processorId);
        });
        if (claim == null) {
            return false;
        }

        // The claim transaction has committed before this second transaction can perform SMTP I/O.
        KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> deliverClaim(session, claim));
        return true;
    }

    private static void deliverClaim(KeycloakSession session, DeliveryClaim claim) {
        EntityManager entityManager = Objects.requireNonNull(
                session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null")
                .getEntityManager();
        JpaAccessRequestNotificationOutboxRepository outbox =
                new JpaAccessRequestNotificationOutboxRepository(entityManager);
        try {
            if (!outbox.ownsActiveClaim(claim.id(), claim.processorId(), Instant.now())) {
                LOG.debugf("Skipping stale access request notification claim %s.", claim.id());
                return;
            }
            RealmModel realm = session.realms().getRealm(claim.realmId());
            AccessRequest request = new JpaAccessRequestRepository(entityManager)
                    .findById(claim.realmId(), claim.requestId())
                    .orElse(null);
            Entitlement entitlement = new JpaEntitlementRepository(entityManager)
                    .findById(claim.realmId(), claim.entitlementId())
                    .orElse(null);
            AccessRequestEventEntity eventEntity = entityManager.find(AccessRequestEventEntity.class, claim.eventId());
            if (realm == null || request == null || entitlement == null || eventEntity == null) {
                outbox.markDiscarded(claim.id(), claim.processorId(), Instant.now());
                return;
            }
            session.getContext().setRealm(realm);
            AccessRequestNotification notification = new AccessRequestNotification(
                    claim.notificationType(),
                    claim.recipientType(),
                    claim.recipientId(),
                    request,
                    entitlement,
                    eventEntity.toDomain());
            if (claim.recipientType() == AccessRequestNotificationRecipientType.REALM_ROLE) {
                if (queueRoleMemberDeliveries(session, realm, outbox, notification, Instant.now())) {
                    outbox.markDelivered(claim.id(), claim.processorId(), Instant.now());
                } else {
                    outbox.markDiscarded(claim.id(), claim.processorId(), Instant.now());
                }
                return;
            }
            KeycloakAccessRequestEmailNotifier.DeliveryResult result =
                    new KeycloakAccessRequestEmailNotifier(session, realm).deliver(notification, claim.recipientId());
            if (result == KeycloakAccessRequestEmailNotifier.DeliveryResult.SENT) {
                outbox.markDelivered(claim.id(), claim.processorId(), Instant.now());
            } else {
                outbox.markDiscarded(claim.id(), claim.processorId(), Instant.now());
            }
        } catch (Exception exception) {
            LOG.warnf(
                    exception,
                    "Could not deliver access request notification %s for request %s.",
                    claim.notificationType(),
                    claim.requestId());
            outbox.markFailed(claim.id(), claim.processorId(), Instant.now(), MAXIMUM_ATTEMPTS);
        }
    }

    private static boolean queueRoleMemberDeliveries(
            KeycloakSession session,
            RealmModel realm,
            JpaAccessRequestNotificationOutboxRepository outbox,
            AccessRequestNotification notification,
            Instant queuedAt) {
        RoleModel role = realm.getRoleById(notification.recipientId());
        if (role == null) {
            return false;
        }

        Set<String> recipientIds = new HashSet<>();
        try (Stream<UserModel> roleMembers = session.users().getRoleMembersStream(realm, role)) {
            roleMembers
                    .filter(KeycloakAccessRequestEmailNotifier::isDeliverable)
                    .map(UserModel::getId)
                    .forEach(recipientIds::add);
        }
        outbox.enqueueIfAbsent(
                notification,
                AccessRequestNotificationRecipientType.USER,
                recipientIds,
                queuedAt);
        return true;
    }

    @FunctionalInterface
    interface IsolatedDeliveryRunner {

        boolean run(KeycloakSessionFactory sessionFactory);
    }

    private record DeliveryClaim(
            String id,
            String processorId,
            String realmId,
            String recipientId,
            AccessRequestNotificationRecipientType recipientType,
            AccessRequestNotificationType notificationType,
            String eventId,
            String requestId,
            String entitlementId) {

        private static DeliveryClaim from(AccessRequestNotificationOutboxEntity entry, String processorId) {
            return new DeliveryClaim(
                    entry.id(),
                    processorId,
                    entry.realmId(),
                    entry.recipientId(),
                    entry.recipientType(),
                    entry.notificationType(),
                    entry.eventId(),
                    entry.requestId(),
                    entry.entitlementId());
        }
    }
}
