package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestRealmResource;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevocationFailureAdministrationEndpointTest {

    @Test
    void exposesRealmScopedOpenAndResolvedFailurePages() {
        Method endpoint = method("listRevocationFailures");
        assertTrue(endpoint.isAnnotationPresent(GET.class));
        assertEquals("admin/revocation-failures", endpoint.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, endpoint.getAnnotation(Produces.class).value()[0]);
        assertEquals("page", endpoint.getParameters()[0].getAnnotation(QueryParam.class).value());
        assertEquals("0", endpoint.getParameters()[0].getAnnotation(DefaultValue.class).value());
        assertEquals("size", endpoint.getParameters()[1].getAnnotation(QueryParam.class).value());
        assertEquals("20", endpoint.getParameters()[1].getAnnotation(DefaultValue.class).value());
        assertEquals("state", endpoint.getParameters()[2].getAnnotation(QueryParam.class).value());
        assertEquals("OPEN", endpoint.getParameters()[2].getAnnotation(DefaultValue.class).value());
    }

    @Test
    void exposesRequestScopedAdministrativeRetryAndVerifiedResolutionWithoutUnsafeClosure() {
        Method endpoint = method("retryGrantRevocation");
        assertTrue(endpoint.isAnnotationPresent(POST.class));
        assertEquals("admin/grants/{requestId}/revocation/retry", endpoint.getAnnotation(Path.class).value());
        assertEquals("requestId", endpoint.getParameters()[0].getAnnotation(PathParam.class).value());
        assertEquals(MediaType.APPLICATION_JSON, endpoint.getAnnotation(Produces.class).value()[0]);
        Method resolution = method("resolveGrantRevocation");
        assertTrue(resolution.isAnnotationPresent(POST.class));
        assertEquals("admin/grants/{requestId}/revocation/resolve", resolution.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, resolution.getAnnotation(Consumes.class).value()[0]);
        assertTrue(Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .noneMatch(candidate -> candidate.getAnnotation(Path.class) != null
                        && candidate.getAnnotation(Path.class).value().contains("revocation/close")));
    }

    private static Method method(String name) {
        return Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }
}
