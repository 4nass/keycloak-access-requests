package ch.anass.keycloak.accessrequests.spi.rest;

import org.junit.jupiter.api.Test;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestRealmResourceProviderTest {

    private static final String FACTORY_ID = "access-requests";
    private static final String FACTORY_CLASS_NAME =
            "ch.anass.keycloak.accessrequests.spi.rest.AccessRequestRealmResourceProviderFactory";
    private static final String RESOURCE_CLASS_NAME =
            "ch.anass.keycloak.accessrequests.spi.rest.AccessRequestRealmResource";
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
        RealmResourceProvider provider = providerFactory().create(null);

        assertNotNull(provider);
        assertEquals(RESOURCE_CLASS_NAME, provider.getResource().getClass().getName());
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
