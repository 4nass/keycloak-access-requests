package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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

class EntitlementCatalogAdministrationEndpointTest {

    private static final Class<?> RESOURCE_TYPE = AccessRequestRealmResource.class;
    private static final String ENTITLEMENT_CREATION_TYPE =
            "ch.anass.keycloak.accessrequests.spi.realm.AccessRequestRealmResource$EntitlementCreation";
    private static final String ENTITLEMENT_UPDATE_TYPE =
            "ch.anass.keycloak.accessrequests.spi.realm.AccessRequestRealmResource$EntitlementUpdate";

    @Test
    void exposesAPaginatedJsonGetHandlerForTheCompleteAdministrativeCatalog() throws Exception {
        Method handler = handler("listCatalogEntitlements");

        assertTrue(handler.isAnnotationPresent(GET.class));
        assertEquals("admin/entitlements", handler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, handler.getReturnType());
        assertPaginationParameters(handler);
    }

    @Test
    void exposesAJsonPostHandlerForEntitlementCreation() throws Exception {
        Method handler = handler("createEntitlement");

        assertTrue(handler.isAnnotationPresent(POST.class));
        assertEquals("admin/entitlements", handler.getAnnotation(Path.class).value());
        assertJsonInputAndOutput(handler, 1, ENTITLEMENT_CREATION_TYPE);
    }

    @Test
    void exposesAJsonGetHandlerForOneAdministrativeEntitlement() throws Exception {
        Method handler = handler("getEntitlement");

        assertTrue(handler.isAnnotationPresent(GET.class));
        assertEquals("admin/entitlements/{entitlementId}", handler.getAnnotation(Path.class).value());
        assertJsonResponse(handler);
        assertPathParameter(handler, 0);
    }

    @Test
    void exposesAJsonPutHandlerForEntitlementMetadataAndRequestabilityUpdates() throws Exception {
        Method handler = handler("updateEntitlement");

        assertTrue(handler.isAnnotationPresent(PUT.class));
        assertEquals("admin/entitlements/{entitlementId}", handler.getAnnotation(Path.class).value());
        assertJsonInputAndOutput(handler, 2, ENTITLEMENT_UPDATE_TYPE);
        assertPathParameter(handler, 0);
    }

    @Test
    void usesAnExplicitCreationPayloadForTheImmutableKeycloakResource() throws Exception {
        Class<?> creation = Class.forName(ENTITLEMENT_CREATION_TYPE);

        assertTrue(creation.isRecord());
        assertArrayEquals(
                new String[]{
                        "resourceType", "resourceId", "displayName", "description", "riskLevel", "approverRoleId"},
                Arrays.stream(creation.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
        assertArrayEquals(
                new Class<?>[]{ResourceType.class, String.class, String.class, String.class, RiskLevel.class, String.class},
                Arrays.stream(creation.getRecordComponents())
                        .map(RecordComponent::getType)
                        .toArray(Class<?>[]::new));
    }

    @Test
    void usesAnUpdatePayloadThatControlsRequestabilityAndGuardsAgainstStaleWrites() throws Exception {
        Class<?> update = Class.forName(ENTITLEMENT_UPDATE_TYPE);

        assertTrue(update.isRecord());
        assertArrayEquals(
                new String[]{"displayName", "description", "riskLevel", "approverRoleId", "requestable", "version"},
                Arrays.stream(update.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
        assertArrayEquals(
                new Class<?>[]{String.class, String.class, RiskLevel.class, String.class, Boolean.class, Long.class},
                Arrays.stream(update.getRecordComponents())
                        .map(RecordComponent::getType)
                        .toArray(Class<?>[]::new));
    }

    @Test
    void doesNotExposePublicationActionEndpoints() {
        assertTrue(Arrays.stream(RESOURCE_TYPE.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("publishEntitlement")
                        || method.getName().equals("unpublishEntitlement")));
    }

    @Test
    void doesNotExposeDeleteForEntitlementsWhoseHistoryMustBePreserved() {
        assertTrue(Arrays.stream(RESOURCE_TYPE.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(DELETE.class)
                        && method.isAnnotationPresent(Path.class)
                        && method.getAnnotation(Path.class).value().startsWith("admin/entitlements")));
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

    private static void assertJsonInputAndOutput(Method handler, int parameterCount, String payloadType) throws Exception {
        assertJsonResponse(handler);
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Consumes.class).value()[0]);
        assertEquals(parameterCount, handler.getParameterCount());
        assertEquals(Class.forName(payloadType), handler.getParameterTypes()[parameterCount - 1]);
    }

    private static void assertJsonResponse(Method handler) {
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, handler.getReturnType());
    }

    private static void assertPathParameter(Method handler, int index) {
        assertEquals(String.class, handler.getParameterTypes()[index]);
        assertEquals("entitlementId", handler.getParameters()[index].getAnnotation(PathParam.class).value());
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
