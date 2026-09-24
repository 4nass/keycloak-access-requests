package ch.anass.keycloak.accessrequests.spi.notification;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Delivers one already queued access request notification through Keycloak's configured email provider.
 */
public final class KeycloakAccessRequestEmailNotifier {

    private final KeycloakSession session;
    private final RealmModel realm;

    public KeycloakAccessRequestEmailNotifier(KeycloakSession session, RealmModel realm) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
    }

    public DeliveryResult deliver(AccessRequestNotification notification, String recipientId) throws EmailException {
        if (notification == null || !realm.getId().equals(notification.request().realmId())) {
            return DeliveryResult.DISCARDED;
        }
        UserModel recipient = session.users().getUserById(realm, recipientId);
        if (!isDeliverable(recipient)) {
            return DeliveryResult.DISCARDED;
        }
        EmailTemplateProvider email = session.getProvider(EmailTemplateProvider.class);
        if (email == null) {
            throw new IllegalStateException("Keycloak email template provider must not be null");
        }
        EmailTemplate template = EmailTemplate.forType(notification.type());
        email.setRealm(realm)
                .setUser(recipient)
                .send(template.subjectKey(), template.fileName(), templateAttributes(notification));
        return DeliveryResult.SENT;
    }

    static boolean isDeliverable(UserModel user) {
        return user != null
                && user.isEnabled()
                && hasText(user.getId())
                && hasText(user.getEmail());
    }

    private static Map<String, Object> templateAttributes(AccessRequestNotification notification) {
        return new HashMap<>(Map.of(
                "request", notification.request(),
                "entitlement", notification.entitlement(),
                "event", notification.event()));
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
                case PROVISIONING_CLOSED -> new EmailTemplate(
                        "accessRequestProvisioningClosedSubject",
                        "access-request-provisioning-closed.ftl");
            };
        }
    }

    public enum DeliveryResult {
        SENT,
        DISCARDED
    }
}
