package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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

class EntitlementCatalogAdministrationEndpointTest {

    private static final Class<?> RESOURCE_TYPE = AccessRequestRealmResource.class;
    private static final String ENTITLEMENT_SUBMISSION_TYPE =
            "ch.anass.keycloak.accessrequests.spi.realm.AccessRequestRealmResource$EntitlementSubmission";

    @Test
    void exposesAJsonGetHandlerForTheCompleteAdministrativeCatalog() throws Exception {
        Method handler = handler("listCatalogEntitlements");

        assertTrue(handler.isAnnotationPresent(GET.class));
        assertEquals("admin/catalog", handler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, handler.getReturnType());
    }

    @Test
    void exposesAJsonPostHandlerForEntitlementCreation() throws Exception {
        Method handler = handler("createEntitlement");

        assertTrue(handler.isAnnotationPresent(POST.class));
        assertEquals("admin/catalog", handler.getAnnotation(Path.class).value());
        assertJsonInputAndOutput(handler, 1);
    }

    @Test
    void exposesAJsonPutHandlerForEntitlementUpdates() throws Exception {
        Method handler = handler("updateEntitlement");

        assertTrue(handler.isAnnotationPresent(PUT.class));
        assertEquals("admin/catalog/{entitlementId}", handler.getAnnotation(Path.class).value());
        assertJsonInputAndOutput(handler, 2);
        assertPathParameter(handler, 0);
    }

    @Test
    void exposesAJsonPostHandlerForEntitlementPublication() throws Exception {
        Method handler = handler("publishEntitlement");

        assertTrue(handler.isAnnotationPresent(POST.class));
        assertEquals("admin/catalog/{entitlementId}/publish", handler.getAnnotation(Path.class).value());
        assertJsonResponse(handler);
        assertPathParameter(handler, 0);
    }

    @Test
    void exposesAJsonPostHandlerForEntitlementUnpublication() throws Exception {
        Method handler = handler("unpublishEntitlement");

        assertTrue(handler.isAnnotationPresent(POST.class));
        assertEquals("admin/catalog/{entitlementId}/unpublish", handler.getAnnotation(Path.class).value());
        assertJsonResponse(handler);
        assertPathParameter(handler, 0);
    }

    @Test
    void usesOnePayloadForCreatingAndUpdatingEntitlementMetadata() throws Exception {
        Class<?> submission = Class.forName(ENTITLEMENT_SUBMISSION_TYPE);

        assertTrue(submission.isRecord());
        assertArrayEquals(
                new String[]{
                        "resourceType", "resourceId", "displayName", "description", "riskLevel", "approverRoleId"},
                Arrays.stream(submission.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
        assertArrayEquals(
                new Class<?>[]{ResourceType.class, String.class, String.class, String.class, RiskLevel.class, String.class},
                Arrays.stream(submission.getRecordComponents())
                        .map(RecordComponent::getType)
                        .toArray(Class<?>[]::new));
    }

    @Test
    void returnsAllAdministrativeMetadataIncludingPublicationStateAndVersion() {
        assertTrue(AccessRequestRealmResource.EntitlementResponse.class.isRecord());
        assertArrayEquals(
                new String[]{
                        "id", "resourceType", "resourceId", "displayName", "description", "riskLevel",
                        "approverRoleId", "requestable", "createdAt", "updatedAt", "version"},
                Arrays.stream(AccessRequestRealmResource.EntitlementResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
    }

    private static Method handler(String name) {
        return Arrays.stream(RESOURCE_TYPE.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The entitlement catalog administration API must expose a " + name + " handler."));
    }

    private static void assertJsonInputAndOutput(Method handler, int parameterCount) throws Exception {
        assertJsonResponse(handler);
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Consumes.class).value()[0]);
        assertEquals(parameterCount, handler.getParameterCount());
        assertEquals(Class.forName(ENTITLEMENT_SUBMISSION_TYPE), handler.getParameterTypes()[parameterCount - 1]);
    }

    private static void assertJsonResponse(Method handler) {
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, handler.getReturnType());
    }

    private static void assertPathParameter(Method handler, int index) {
        assertEquals(String.class, handler.getParameterTypes()[index]);
        assertEquals("entitlementId", handler.getParameters()[index].getAnnotation(PathParam.class).value());
    }
}
