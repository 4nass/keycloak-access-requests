package ch.anass.keycloak.accessrequests.spi.notification;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestNotificationPublisher;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestNotificationOutboxRepository;
import jakarta.persistence.EntityManager;
import org.keycloak.models.RealmModel;

import java.time.Instant;
import java.util.Objects;

/**
 * Adds lifecycle email deliveries to the current database transaction without performing SMTP I/O.
 */
public final class KeycloakAccessRequestNotificationOutboxPublisher implements AccessRequestNotificationPublisher {

    private final RealmModel realm;
    private final JpaAccessRequestNotificationOutboxRepository outbox;

    public KeycloakAccessRequestNotificationOutboxPublisher(
            RealmModel realm,
            EntityManager entityManager) {
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
        this.outbox = new JpaAccessRequestNotificationOutboxRepository(
                Objects.requireNonNull(entityManager, "entityManager must not be null"));
    }

    @Override
    public void publish(AccessRequestNotification notification) {
        if (notification == null || !realm.getId().equals(notification.request().realmId())) {
            return;
        }
        outbox.enqueue(notification, notification.recipientType(), notification.recipientId(), Instant.now());
    }
}
