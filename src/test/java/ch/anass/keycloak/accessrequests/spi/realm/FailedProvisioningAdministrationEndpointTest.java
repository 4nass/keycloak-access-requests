package ch.anass.keycloak.accessrequests.spi.realm;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedProvisioningAdministrationEndpointTest {

    @Test
    void exposesAPaginatedAdministrativeListOfFailedProvisioningRequests() {
        Method handler = handler("listFailedProvisioningRequests");

        assertTrue(handler.isAnnotationPresent(GET.class));
        assertEquals("admin/provisioning-failures", handler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, handler.getReturnType());
        assertPaginationParameters(handler);
    }

    @Test
    void returnsThePagingEnvelopeAndOnlyActionableRequestMetadata() throws Exception {
        assertArrayEquals(
                new String[]{"items", "page", "size", "total"},
                Arrays.stream(responseType("FailedProvisioningRequestListResponse").getRecordComponents())
                        .map(component -> component.getName())
                        .toArray(String[]::new));
        assertArrayEquals(
                new String[]{
                        "id", "requesterId", "entitlementId", "resourceType", "resourceName",
                        "decisionStatus", "provisioningStatus", "updatedAt", "failureCode",
                        "closedAt", "closedBy", "closureReason"},
                Arrays.stream(responseType("FailedProvisioningRequestResponse").getRecordComponents())
                        .map(component -> component.getName())
                        .toArray(String[]::new));
    }

    @Test
    void exposesARequestScopedClosureWithMandatoryReason() throws Exception {
        Method handler = handler("closeFailedProvisioning");
        assertTrue(handler.isAnnotationPresent(POST.class));
        assertEquals("admin/requests/{requestId}/provisioning/close", handler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Consumes.class).value()[0]);
        assertEquals(Response.class, handler.getReturnType());
        assertArrayEquals(new String[]{"reason"}, Arrays.stream(responseType("ProvisioningClosureSubmission")
                .getRecordComponents()).map(component -> component.getName()).toArray(String[]::new));
    }

    private static Class<?> responseType(String name) throws ClassNotFoundException {
        return Class.forName(AccessRequestRealmResource.class.getName() + "$" + name);
    }

    private static Method handler(String name) {
        return Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The access request administration API must expose a " + name + " handler."));
    }

    private static void assertPaginationParameters(Method handler) {
        assertEquals(3, handler.getParameterCount());
        assertEquals(int.class, handler.getParameterTypes()[0]);
        assertEquals("page", handler.getParameters()[0].getAnnotation(QueryParam.class).value());
        assertEquals("0", handler.getParameters()[0].getAnnotation(DefaultValue.class).value());
        assertEquals(int.class, handler.getParameterTypes()[1]);
        assertEquals("size", handler.getParameters()[1].getAnnotation(QueryParam.class).value());
        assertEquals("20", handler.getParameters()[1].getAnnotation(DefaultValue.class).value());
        assertEquals(String.class, handler.getParameterTypes()[2]);
        assertEquals("state", handler.getParameters()[2].getAnnotation(QueryParam.class).value());
        assertEquals("OPEN", handler.getParameters()[2].getAnnotation(DefaultValue.class).value());
    }
}
