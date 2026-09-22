package ch.anass.keycloak.accessrequests.spi.notification;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestNotificationPublisher;
import org.jboss.logging.Logger;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Delivers access request lifecycle notifications through Keycloak's configured email provider.
 */
public final class KeycloakAccessRequestEmailNotifier implements AccessRequestNotificationPublisher {

    private static final Logger LOG = Logger.getLogger(KeycloakAccessRequestEmailNotifier.class);

    private final KeycloakSession session;
    private final RealmModel realm;

    public KeycloakAccessRequestEmailNotifier(KeycloakSession session, RealmModel realm) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
    }

    @Override
    public void publish(AccessRequestNotification notification) {
        if (notification == null || !realm.getId().equals(notification.request().realmId())) {
            return;
        }

        Set<String> deliveredUserIds = new HashSet<>();
        try {
            switch (notification.recipientType()) {
                case USER -> deliverUser(notification.recipientId(), notification, deliveredUserIds);
                case REALM_ROLE -> deliverRoleMembers(notification.recipientId(), notification, deliveredUserIds);
            }
        } catch (RuntimeException exception) {
            logDeliveryFailure(notification, exception);
        }
    }

    private void deliverRoleMembers(
            String roleId,
            AccessRequestNotification notification,
            Set<String> deliveredUserIds) {
        RoleModel role = realm.getRoleById(roleId);
        if (role == null) {
            return;
        }
        try (Stream<UserModel> members = session.users().getRoleMembersStream(realm, role)) {
            members.forEach(member -> deliver(member, notification, deliveredUserIds));
        }
    }

    private void deliverUser(
            String userId,
            AccessRequestNotification notification,
            Set<String> deliveredUserIds) {
        deliver(session.users().getUserById(realm, userId), notification, deliveredUserIds);
    }

    private void deliver(
            UserModel recipient,
            AccessRequestNotification notification,
            Set<String> deliveredUserIds) {
        try {
            if (!isDeliverable(recipient) || !deliveredUserIds.add(recipient.getId())) {
                return;
            }
            EmailTemplateProvider email = session.getProvider(EmailTemplateProvider.class);
            if (email == null) {
                return;
            }
            EmailTemplate template = EmailTemplate.forType(notification.type());
            email.setRealm(realm)
                    .setUser(recipient)
                    .send(template.subjectKey(), template.fileName(), templateAttributes(notification));
        } catch (EmailException | RuntimeException exception) {
            logDeliveryFailure(notification, exception);
        }
    }

    private static boolean isDeliverable(UserModel user) {
        return user != null
                && user.isEnabled()
                && hasText(user.getId())
                && hasText(user.getEmail());
    }

    private static Map<String, Object> templateAttributes(AccessRequestNotification notification) {
        return Map.of(
                "request", notification.request(),
                "entitlement", notification.entitlement(),
                "event", notification.event());
    }

    private void logDeliveryFailure(AccessRequestNotification notification, Exception exception) {
        LOG.warnf(
                exception,
                "Could not deliver access request notification %s for request %s.",
                notification.type(),
                notification.request().id());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record EmailTemplate(String subjectKey, String fileName) {

        private static EmailTemplate forType(AccessRequestNotificationType type) {
            return switch (type) {
                case REQUEST_SUBMITTED -> new EmailTemplate(
                        "accessRequestSubmittedSubject",
                        "access-request-submitted.ftl");
                case REQUEST_APPROVED -> new EmailTemplate(
                        "accessRequestApprovedSubject",
                        "access-request-approved.ftl");
                case REQUEST_REJECTED -> new EmailTemplate(
                        "accessRequestRejectedSubject",
                        "access-request-rejected.ftl");
                case PROVISIONING_FAILED -> new EmailTemplate(
                        "accessRequestProvisioningFailedSubject",
                        "access-request-provisioning-failed.ftl");
            };
        }
    }
}
