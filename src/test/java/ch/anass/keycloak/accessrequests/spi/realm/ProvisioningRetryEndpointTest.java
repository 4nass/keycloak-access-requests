package ch.anass.keycloak.accessrequests.spi.realm;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvisioningRetryEndpointTest {

    @Test
    void exposesAnAdministrativePostEndpointToRetryProvisioning() {
        Method handler = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("retryFailedProvisioning"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The admin API must expose a failed provisioning retry endpoint."));

        assertTrue(handler.isAnnotationPresent(POST.class));
        assertEquals("admin/requests/{requestId}/provisioning/retry", handler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, handler.getReturnType());
        assertEquals(1, handler.getParameterCount());
        assertEquals(String.class, handler.getParameterTypes()[0]);
        assertEquals("requestId", handler.getParameters()[0].getAnnotation(PathParam.class).value());
    }
}
