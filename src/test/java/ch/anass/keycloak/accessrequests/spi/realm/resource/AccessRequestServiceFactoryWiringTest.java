package ch.anass.keycloak.accessrequests.spi.realm.resource;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertSame;

class AccessRequestServiceFactoryWiringTest {

    @Test
    void sharesOneFactoryAndSessionAcrossAllRealmHandlers() throws ReflectiveOperationException {
        KeycloakSession session = (KeycloakSession) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{KeycloakSession.class},
                (proxy, method, arguments) -> null);
        AccessRequestRealmResource resource = new AccessRequestRealmResource(session);

        AccessRequestServiceFactory catalog = services(handler(resource, "catalogHandler"));
        assertSame(session, catalog.session());
        assertSame(catalog, services(handler(resource, "requesterHandler")));
        assertSame(catalog, services(handler(resource, "approvalHandler")));
        assertSame(catalog, services(handler(resource, "adminHandler")));
    }

    private static AccessRequestHandlerSupport handler(AccessRequestRealmResource resource, String name)
            throws ReflectiveOperationException {
        Field field = AccessRequestRealmResource.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AccessRequestHandlerSupport) field.get(resource);
    }

    private static AccessRequestServiceFactory services(AccessRequestHandlerSupport handler)
            throws ReflectiveOperationException {
        Field field = AccessRequestHandlerSupport.class.getDeclaredField("services");
        field.setAccessible(true);
        return (AccessRequestServiceFactory) field.get(handler);
    }
}
