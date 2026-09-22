package ch.anass.keycloak.accessrequests.spi.notification;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestNotificationPublisher;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestNotificationOutboxRepository;
import jakarta.persistence.EntityManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Adds lifecycle email deliveries to the current database transaction without performing SMTP I/O.
 */
public final class KeycloakAccessRequestNotificationOutboxPublisher implements AccessRequestNotificationPublisher {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final JpaAccessRequestNotificationOutboxRepository outbox;

    public KeycloakAccessRequestNotificationOutboxPublisher(
            KeycloakSession session,
            RealmModel realm,
            EntityManager entityManager) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.outbox = new JpaAccessRequestNotificationOutboxRepository(
                Objects.requireNonNull(entityManager, "entityManager must not be null"));
    }

    @Override
    public void publish(AccessRequestNotification notification) {
        if (notification == null || !realm.getId().equals(notification.request().realmId())) {
            return;
        }
        Set<String> recipientIds = resolveDeliverableRecipientIds(notification);
        Instant queuedAt = Instant.now();
        recipientIds.forEach(recipientId -> outbox.enqueue(notification, recipientId, queuedAt));
    }

    private Set<String> resolveDeliverableRecipientIds(AccessRequestNotification notification) {
        Set<String> recipientIds = new HashSet<>();
        if (notification.recipientType() == AccessRequestNotificationRecipientType.USER) {
            addIfDeliverable(session.users().getUserById(realm, notification.recipientId()), recipientIds);
            return recipientIds;
        }
        RoleModel role = realm.getRoleById(notification.recipientId());
        if (role == null) {
            return recipientIds;
        }
        try (Stream<UserModel> members = session.users().getRoleMembersStream(realm, role)) {
            members.forEach(member -> addIfDeliverable(member, recipientIds));
        }
        return recipientIds;
    }

    private static void addIfDeliverable(UserModel user, Set<String> recipientIds) {
        if (KeycloakAccessRequestEmailNotifier.isDeliverable(user)) {
            recipientIds.add(user.getId());
        }
    }
}
