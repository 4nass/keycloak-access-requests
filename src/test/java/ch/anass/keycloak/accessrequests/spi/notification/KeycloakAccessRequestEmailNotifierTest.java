package ch.anass.keycloak.accessrequests.spi.notification;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakAccessRequestEmailNotifierTest {

    @ParameterizedTest
    @MethodSource("notificationTemplates")
    void sendsEachLifecycleNotificationWithItsLocalizedSubjectAndTemplate(
            AccessRequestNotificationType type,
            String subjectKey,
            String template) {
        KeycloakFixture fixture = KeycloakFixture.withUser("requester-1", true, "requester@example.test");

        fixture.notifier().publish(fixture.notification(type, AccessRequestNotificationRecipientType.USER, "requester-1"));

        fixture.assertDelivery("requester-1", subjectKey, template);
        fixture.assertTemplateContext();
    }

    @Test
    void sendsSubmittedNotificationsToEveryDeliverableApproverInTheConfiguredRealmRole() {
        KeycloakFixture fixture = KeycloakFixture.withRoleMembers(
                "finance-approver",
                user("approver-1", true, "approver-1@example.test"),
                user("approver-2", true, "approver-2@example.test"),
                user("disabled-approver", false, "disabled@example.test"),
                user("missing-email", true, null));

        fixture.notifier().publish(fixture.notification(
                AccessRequestNotificationType.REQUEST_SUBMITTED,
                AccessRequestNotificationRecipientType.REALM_ROLE,
                "finance-approver"));

        assertEquals(List.of("approver-1", "approver-2"), fixture.deliveredRecipientIds());
        fixture.assertDelivery("approver-1", "accessRequestSubmittedSubject", "access-request-submitted.ftl");
        fixture.assertDelivery("approver-2", "accessRequestSubmittedSubject", "access-request-submitted.ftl");
    }

    @Test
    void ignoresUnknownRecipientsAndNotificationsFromAnotherRealm() {
        KeycloakFixture fixture = KeycloakFixture.withUser("requester-1", true, "requester@example.test");

        assertDoesNotThrow(() -> fixture.notifier().publish(fixture.notification(
                AccessRequestNotificationType.REQUEST_APPROVED,
                AccessRequestNotificationRecipientType.USER,
                "unknown-user")));
        assertDoesNotThrow(() -> fixture.notifier().publish(fixture.notification(
                AccessRequestNotificationType.REQUEST_SUBMITTED,
                AccessRequestNotificationRecipientType.REALM_ROLE,
                "unknown-role")));
        assertDoesNotThrow(() -> fixture.notifier().publish(fixture.notificationInRealm(
                "another-realm",
                AccessRequestNotificationType.REQUEST_APPROVED,
                AccessRequestNotificationRecipientType.USER,
                "requester-1")));

        assertTrue(fixture.deliveries().isEmpty());
    }

    @Test
    void isolatesEmailFailuresSoOneRecipientCannotBlockTheOthersOrTheRequestWorkflow() {
        KeycloakFixture fixture = KeycloakFixture.withRoleMembers(
                "finance-approver",
                user("unreachable-approver", true, "unreachable@example.test"),
                user("reachable-approver", true, "reachable@example.test"));
        fixture.failDeliveryTo("unreachable-approver");

        assertDoesNotThrow(() -> fixture.notifier().publish(fixture.notification(
                AccessRequestNotificationType.REQUEST_SUBMITTED,
                AccessRequestNotificationRecipientType.REALM_ROLE,
                "finance-approver")));

        assertEquals(List.of("reachable-approver"), fixture.deliveredRecipientIds());
    }

    private static Stream<Arguments> notificationTemplates() {
        return Stream.of(
                Arguments.of(
                        AccessRequestNotificationType.REQUEST_SUBMITTED,
                        "accessRequestSubmittedSubject",
                        "access-request-submitted.ftl"),
                Arguments.of(
                        AccessRequestNotificationType.REQUEST_APPROVED,
                        "accessRequestApprovedSubject",
                        "access-request-approved.ftl"),
                Arguments.of(
                        AccessRequestNotificationType.REQUEST_REJECTED,
                        "accessRequestRejectedSubject",
                        "access-request-rejected.ftl"),
                Arguments.of(
                        AccessRequestNotificationType.PROVISIONING_FAILED,
                        "accessRequestProvisioningFailedSubject",
                        "access-request-provisioning-failed.ftl"));
    }

    private static UserModel user(String id, boolean enabled, String email) {
        return proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> id;
            case "isEnabled" -> enabled;
            case "getEmail" -> email;
            default -> null;
        });
    }

    private static final class KeycloakFixture {

        private static final String REALM_ID = "realm-1";
        private final Map<String, UserModel> users = new HashMap<>();
        private final Map<String, RoleModel> roles = new HashMap<>();
        private final Map<String, List<UserModel>> roleMembers = new HashMap<>();
        private final List<Delivery> deliveries = new ArrayList<>();
        private String failingRecipientId;
        private UserModel emailRecipient;

        static KeycloakFixture withUser(String id, boolean enabled, String email) {
            KeycloakFixture fixture = new KeycloakFixture();
            fixture.users.put(id, user(id, enabled, email));
            return fixture;
        }

        static KeycloakFixture withRoleMembers(String roleId, UserModel... members) {
            KeycloakFixture fixture = new KeycloakFixture();
            RoleModel role = proxy(RoleModel.class, (proxy, method, arguments) ->
                    method.getName().equals("getId") ? roleId : null);
            fixture.roles.put(roleId, role);
            fixture.roleMembers.put(roleId, List.of(members));
            return fixture;
        }

        KeycloakAccessRequestEmailNotifier notifier() {
            return new KeycloakAccessRequestEmailNotifier(session(), realm());
        }

        AccessRequestNotification notification(
                AccessRequestNotificationType type,
                AccessRequestNotificationRecipientType recipientType,
                String recipientId) {
            return notificationInRealm(REALM_ID, type, recipientType, recipientId);
        }

        AccessRequestNotification notificationInRealm(
                String realmId,
                AccessRequestNotificationType type,
                AccessRequestNotificationRecipientType recipientType,
                String recipientId) {
            Instant now = Instant.parse("2026-09-22T10:00:00Z");
            Entitlement entitlement = Entitlement.create(
                    "entitlement-1",
                    realmId,
                    ResourceType.REALM_ROLE,
                    "managed-role",
                    "Finance reader",
                    "Read access to finance data.",
                    RiskLevel.HIGH,
                    "finance-approver",
                    now);
            AccessRequest request = AccessRequest.create(
                    "request-1",
                    realmId,
                    "requester-1",
                    entitlement.id(),
                    entitlement.resourceType(),
                    entitlement.resourceId(),
                    entitlement.displayName(),
                    "Need read access for month-end close.",
                    now);
            AccessRequestEvent event = event(type, request, now);
            return new AccessRequestNotification(type, recipientType, recipientId, request, entitlement, event);
        }

        void failDeliveryTo(String userId) {
            failingRecipientId = userId;
        }

        List<Delivery> deliveries() {
            return deliveries;
        }

        List<String> deliveredRecipientIds() {
            return deliveries.stream().map(Delivery::recipientId).toList();
        }

        void assertDelivery(String recipientId, String subjectKey, String template) {
            assertTrue(deliveries.stream().anyMatch(delivery -> delivery.recipientId().equals(recipientId)
                    && delivery.subjectKey().equals(subjectKey)
                    && delivery.template().equals(template)));
        }

        void assertTemplateContext() {
            Delivery delivery = deliveries.getFirst();
            assertEquals(Set.of("request", "entitlement", "event"), delivery.attributes().keySet());
            assertEquals("request-1", ((AccessRequest) delivery.attributes().get("request")).id());
            assertEquals("entitlement-1", ((Entitlement) delivery.attributes().get("entitlement")).id());
            assertEquals("request-1", ((AccessRequestEvent) delivery.attributes().get("event")).requestId());
        }

        private RealmModel realm() {
            return proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "getId" -> REALM_ID;
                case "getRoleById" -> roles.get(arguments[0]);
                default -> null;
            });
        }

        @SuppressWarnings("unchecked")
        private KeycloakSession session() {
            UserProvider userProvider = proxy(UserProvider.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "getUserById" -> users.get(arguments[1]);
                case "getRoleMembersStream" -> roleMembers.getOrDefault(
                        ((RoleModel) arguments[1]).getId(),
                        List.of()).stream();
                default -> null;
            });
            EmailTemplateProvider emailTemplateProvider = proxy(EmailTemplateProvider.class, (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "setRealm" -> {
                        return proxy;
                    }
                    case "setUser" -> {
                        emailRecipient = (UserModel) arguments[0];
                        return proxy;
                    }
                    case "send" -> {
                        if (emailRecipient.getId().equals(failingRecipientId)) {
                            throw new EmailException("SMTP rejected the message");
                        }
                        Map<String, Object> attributes = (Map<String, Object>) arguments[2];
                        attributes.put("keycloakThemeAttribute", "theme");
                        attributes.remove("keycloakThemeAttribute");
                        deliveries.add(new Delivery(
                                emailRecipient.getId(),
                                (String) arguments[0],
                                (String) arguments[1],
                                Map.copyOf(attributes)));
                        return null;
                    }
                    default -> {
                        return null;
                    }
                }
            });
            return proxy(KeycloakSession.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "users" -> userProvider;
                case "getProvider" -> arguments[0] == EmailTemplateProvider.class ? emailTemplateProvider : null;
                default -> null;
            });
        }

        private static AccessRequestEvent event(
                AccessRequestNotificationType type,
                AccessRequest request,
                Instant occurredAt) {
            return switch (type) {
                case REQUEST_SUBMITTED -> AccessRequestEvent.created(request, request.requesterId(), occurredAt);
                case REQUEST_APPROVED -> AccessRequestEvent.approved(
                        request,
                        "approver-1",
                        occurredAt,
                        "Approved for month-end close.");
                case REQUEST_REJECTED -> AccessRequestEvent.rejected(
                        request,
                        "approver-1",
                        occurredAt,
                        "A separate approval is required.");
                case PROVISIONING_FAILED -> AccessRequestEvent.provisioningFailed(
                        request,
                        "system",
                        occurredAt,
                        "The configured Keycloak role no longer exists.");
            };
        }
    }

    private record Delivery(String recipientId, String subjectKey, String template, Map<String, Object> attributes) {
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
