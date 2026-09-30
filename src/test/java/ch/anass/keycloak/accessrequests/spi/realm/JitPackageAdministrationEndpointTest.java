package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestRealmResource;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JitPackageAdministrationEndpointTest {

    @Test
    void exposesDedicatedJsonCreationWithoutRetrofittingAnExistingEntitlement() {
        Method endpoint = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(POST.class)
                        && method.isAnnotationPresent(Path.class)
                        && "admin/entitlements/jit-packages".equals(method.getAnnotation(Path.class).value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("JIT packages need an atomic admin creation endpoint"));

        assertEquals(Response.class, endpoint.getReturnType());
        assertEquals(MediaType.APPLICATION_JSON, endpoint.getAnnotation(Consumes.class).value()[0]);
        assertEquals(MediaType.APPLICATION_JSON, endpoint.getAnnotation(Produces.class).value()[0]);
        assertEquals(1, endpoint.getParameterCount());
        Class<?> payload = endpoint.getParameterTypes()[0];
        assertTrue(payload.isRecord(), "The JSON request must have an explicit immutable shape");
        assertArrayEquals(new String[]{"displayName", "description", "riskLevel", "approverRoleId",
                        "defaultDurationSeconds", "maxDurationSeconds", "allowPermanent", "roleMappings"},
                Arrays.stream(payload.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new));
        assertEquals(java.util.List.class, payload.getRecordComponents()[7].getType());
    }
}
