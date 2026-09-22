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
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

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
    void queuesDeliveriesInTheCurrentTransactionWithoutCallingTheEmailProvider() {
        AtomicBoolean emailProviderRequested = new AtomicBoolean();
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            publisher(entityManager, emailProviderRequested, Map.of("requester-1", user("requester-1", true, "requester@test")), Map.of())
                    .publish(notification(AccessRequestNotificationRecipientType.USER, "requester-1"));

            assertFalse(emailProviderRequested.get());
            entityManager.getTransaction().rollback();

            Long queued = entityManager.createQuery(
                            "select count(entry) from AccessRequestNotificationOutboxEntity entry", Long.class)
                    .getSingleResult();
            assertEquals(0L, queued);
        }
    }

    @Test
    void queuesOneDurableDeliveryForEachEligibleRoleMember() {
        AtomicBoolean emailProviderRequested = new AtomicBoolean();
        Map<String, UserModel> users = Map.of(
                "approver-1", user("approver-1", true, "approver-1@test"),
                "approver-2", user("approver-2", true, "approver-2@test"),
                "disabled", user("disabled", false, "disabled@test"),
                "without-email", user("without-email", true, null));
        RoleModel role = role("approver-role");
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            publisher(entityManager, emailProviderRequested, users, Map.of(role.getId(), List.copyOf(users.values())))
                    .publish(notification(AccessRequestNotificationRecipientType.REALM_ROLE, role.getId()));
            entityManager.getTransaction().commit();

            List<String> recipients = entityManager.createQuery(
                            "select entry.recipientId from AccessRequestNotificationOutboxEntity entry order by entry.recipientId",
                            String.class)
                    .getResultList();
            assertEquals(List.of("approver-1", "approver-2"), recipients);
            assertFalse(emailProviderRequested.get());
        }
    }

    private KeycloakAccessRequestNotificationOutboxPublisher publisher(
            EntityManager entityManager,
            AtomicBoolean emailProviderRequested,
            Map<String, UserModel> users,
            Map<String, List<UserModel>> roleMembers) {
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> REALM_ID;
            case "getRoleById" -> role((String) arguments[0]);
            default -> null;
        });
        UserProvider userProvider = proxy(UserProvider.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getUserById" -> users.get(arguments[1]);
            case "getRoleMembersStream" -> roleMembers.getOrDefault(
                    ((RoleModel) arguments[1]).getId(), List.of()).stream();
            default -> null;
        });
        KeycloakSession session = proxy(KeycloakSession.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "users" -> userProvider;
            case "getProvider" -> {
                if (arguments[0] == EmailTemplateProvider.class) {
                    emailProviderRequested.set(true);
                }
                yield null;
            }
            default -> null;
        });
        return new KeycloakAccessRequestNotificationOutboxPublisher(session, realm, entityManager);
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

    private static RoleModel role(String id) {
        return proxy(RoleModel.class, (proxy, method, arguments) ->
                method.getName().equals("getId") ? id : null);
    }

    private static UserModel user(String id, boolean enabled, String email) {
        return proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> id;
            case "isEnabled" -> enabled;
            case "getEmail" -> email;
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
