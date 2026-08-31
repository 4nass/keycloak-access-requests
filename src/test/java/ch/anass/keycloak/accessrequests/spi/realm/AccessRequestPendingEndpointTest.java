package ch.anass.keycloak.accessrequests.spi.realm;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestPendingEndpointTest {

    private static final Class<?> RESOURCE_TYPE = AccessRequestRealmResource.class;

    @Test
    void exposesAJsonGetHandlerForTheAuthenticatedApproversPendingRequests() {
        Method pendingHandler = handler();

        assertTrue(pendingHandler.isAnnotationPresent(GET.class));
        assertEquals("pending", pendingHandler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, pendingHandler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, pendingHandler.getReturnType());
    }

    @Test
    void acceptsTheStandardPaginationParametersForThePendingRequestQueue() {
        Method pendingHandler = handler();

        assertEquals(2, pendingHandler.getParameterCount());
        assertQueryParameter(pendingHandler, 0, "page", "0");
        assertQueryParameter(pendingHandler, 1, "size", "20");
    }

    private static Method handler() {
        return Arrays.stream(RESOURCE_TYPE.getDeclaredMethods())
                .filter(method -> method.getName().equals("listPendingRequests"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The access request API must expose a pending request queue handler."));
    }

    private static void assertQueryParameter(Method handler, int parameterIndex, String name, String defaultValue) {
        assertEquals(int.class, handler.getParameterTypes()[parameterIndex]);
        assertEquals(name, handler.getParameters()[parameterIndex].getAnnotation(QueryParam.class).value());
        assertEquals(defaultValue, handler.getParameters()[parameterIndex].getAnnotation(DefaultValue.class).value());
    }
}
