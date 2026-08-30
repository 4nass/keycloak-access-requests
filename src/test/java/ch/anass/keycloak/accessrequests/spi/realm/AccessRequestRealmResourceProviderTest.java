package ch.anass.keycloak.accessrequests.spi.realm;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestRealmResourceProviderTest {

    private static final String FACTORY_ID = "access-requests";
    private static final String FACTORY_CLASS_NAME =
            "ch.anass.keycloak.accessrequests.spi.realm.AccessRequestRealmResourceProviderFactory";
    private static final String RESOURCE_CLASS_NAME =
            "ch.anass.keycloak.accessrequests.spi.realm.AccessRequestRealmResource";
    private static final String SERVICE_CONFIGURATION =
            "META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory";

    @Test
    void registersTheAccessRequestRealmEndpointThroughKeycloakSpi() throws Exception {
        assertTrue(
                Collections.list(getClass().getClassLoader().getResources(SERVICE_CONFIGURATION)).stream()
                        .flatMap(this::registrations)
                        .anyMatch(FACTORY_CLASS_NAME::equals),
                "The realm resource provider must be registered for Keycloak.");
    }

    @Test
    void discoversTheAccessRequestRealmEndpointFactory() {
        assertEquals(FACTORY_CLASS_NAME, providerFactory().getClass().getName());
    }

    @Test
    void usesAccessRequestsAsTheRealmEndpointPath() {
        assertEquals(FACTORY_ID, providerFactory().getId());
    }

    @Test
    void exposesTheAccessRequestJaxRsResource() {
        RealmResourceProvider provider = providerFactory().create(keycloakSession());

        assertNotNull(provider);
        assertEquals(RESOURCE_CLASS_NAME, provider.getResource().getClass().getName());
    }

    @Test
    void rejectsRealmEndpointCreationWithoutAKeycloakSession() {
        assertThrows(NullPointerException.class, () -> providerFactory().create(null));
    }

    @Test
    void exposesAnOptionsJaxRsHandlerForTheCatalogEndpoint() {
        var optionsHandler = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("catalogOptions"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The catalog endpoint must expose an OPTIONS handler."));

        assertTrue(optionsHandler.isAnnotationPresent(OPTIONS.class));
        assertEquals("catalog", optionsHandler.getAnnotation(Path.class).value());
    }

    @Test
    void exposesAJsonGetHandlerForTheCatalogEndpoint() {
        var catalogHandler = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("catalog"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The catalog endpoint must expose a GET handler."));

        assertTrue(catalogHandler.isAnnotationPresent(GET.class));
        assertEquals("catalog", catalogHandler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, catalogHandler.getAnnotation(Produces.class).value()[0]);
    }

    @Test
    void exposesAJsonPostHandlerForRequestSubmission() {
        var submitHandler = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("submitRequest"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The request submission endpoint must expose a POST handler."));

        assertTrue(submitHandler.isAnnotationPresent(POST.class));
        assertEquals("requests", submitHandler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, submitHandler.getAnnotation(Consumes.class).value()[0]);
        assertEquals(MediaType.APPLICATION_JSON, submitHandler.getAnnotation(Produces.class).value()[0]);
    }

    @Test
    void exposesAJsonGetHandlerForTheAuthenticatedRequestersRequests() {
        var listHandler = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("listRequests"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The requester request list must expose a GET handler."));

        assertTrue(listHandler.isAnnotationPresent(GET.class));
        assertEquals("requests", listHandler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, listHandler.getAnnotation(Produces.class).value()[0]);
    }

    @Test
    void exposesADeleteHandlerForRequesterCancellation() {
        var cancelHandler = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("cancelRequest"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The request cancellation endpoint must expose a DELETE handler."));

        assertTrue(cancelHandler.isAnnotationPresent(DELETE.class));
        assertEquals("requests/{requestId}", cancelHandler.getAnnotation(Path.class).value());
    }

    private RealmResourceProviderFactory providerFactory() {
        return ServiceLoader.load(RealmResourceProviderFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(factory -> FACTORY_CLASS_NAME.equals(factory.getClass().getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The access request realm endpoint must be registered through the Keycloak SPI."));
    }

    private KeycloakSession keycloakSession() {
        return (KeycloakSession) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{KeycloakSession.class},
                (proxy, method, arguments) -> null);
    }

    private java.util.stream.Stream<String> registrations(java.net.URL serviceConfiguration) {
        try (var stream = serviceConfiguration.openStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .toList()
                    .stream();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Keycloak SPI registration.", exception);
        }
    }
}
