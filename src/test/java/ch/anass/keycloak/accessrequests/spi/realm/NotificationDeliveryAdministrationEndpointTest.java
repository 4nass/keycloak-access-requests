package ch.anass.keycloak.accessrequests.spi.realm;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationDeliveryAdministrationEndpointTest {

    private static final Class<?> RESOURCE_TYPE = AccessRequestRealmResource.class;

    @Test
    void exposesAPaginatedAdministrativeViewOfFailedDeliveries() throws Exception {
        Method handler = handler("listFailedNotificationDeliveries");

        assertTrue(handler.isAnnotationPresent(GET.class));
        assertEquals("admin/notification-deliveries", handler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, handler.getReturnType());
        assertPaginationParameters(handler);
    }

    @Test
    void exposesLowCardinalityDeliveryCountsForRealmOperations() throws Exception {
        Method handler = handler("notificationDeliverySummary");

        assertTrue(handler.isAnnotationPresent(GET.class));
        assertEquals("admin/notification-deliveries/summary", handler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(AccessRequestRealmResource.NotificationDeliverySummaryResponse.class, handler.getReturnType());
        assertArrayEquals(
                new String[]{"pending", "processing", "delivered", "discarded", "failed"},
                Arrays.stream(AccessRequestRealmResource.NotificationDeliverySummaryResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
    }

    @Test
    void exposesAnIdempotenceSafeRetryActionForTerminalFailuresOnly() throws Exception {
        Method handler = handler("retryFailedNotificationDelivery");

        assertTrue(handler.isAnnotationPresent(POST.class));
        assertEquals("admin/notification-deliveries/{deliveryId}/retry", handler.getAnnotation(Path.class).value());
        assertEquals(Response.class, handler.getReturnType());
        assertEquals(1, handler.getParameterCount());
        assertEquals(String.class, handler.getParameterTypes()[0]);
        assertEquals("deliveryId", handler.getParameters()[0].getAnnotation(PathParam.class).value());
    }

    @Test
    void returnsOnlyOperationalMetadataAndNeverTheRecipientEmailAddress() {
        assertArrayEquals(
                new String[]{
                        "id", "requestId", "entitlementId", "recipientId", "recipientType",
                        "notificationType", "attemptCount", "lastAttemptAt"},
                Arrays.stream(AccessRequestRealmResource.NotificationDeliveryResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
    }

    @Test
    void advertisesNotificationOperationsAlongsideCatalogManagement() {
        assertArrayEquals(
                new String[]{"canManageCatalog", "canManageNotifications", "canManageProvisioningFailures"},
                Arrays.stream(AccessRequestRealmResource.AdminCapabilitiesResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
    }

    private static Method handler(String name) {
        return Arrays.stream(RESOURCE_TYPE.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The notification delivery administration API must expose a " + name + " handler."));
    }

    private static void assertPaginationParameters(Method handler) {
        assertEquals(2, handler.getParameterCount());
        assertEquals(int.class, handler.getParameterTypes()[0]);
        assertEquals("page", handler.getParameters()[0].getAnnotation(QueryParam.class).value());
        assertEquals("0", handler.getParameters()[0].getAnnotation(DefaultValue.class).value());
        assertEquals(int.class, handler.getParameterTypes()[1]);
        assertEquals("size", handler.getParameters()[1].getAnnotation(QueryParam.class).value());
        assertEquals("20", handler.getParameters()[1].getAnnotation(DefaultValue.class).value());
    }
}
