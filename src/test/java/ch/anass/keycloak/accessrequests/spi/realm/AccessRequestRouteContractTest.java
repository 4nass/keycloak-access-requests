package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestRealmResource;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessRequestRouteContractTest {

    @Test
    void keepsTheExistingRealmUrlsAndHttpMethods() {
        Set<Route> routes = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Path.class))
                .map(AccessRequestRouteContractTest::route)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                new Route("GET", "catalog"),
                new Route("OPTIONS", "catalog"),
                new Route("GET", "admin/entitlements"),
                new Route("POST", "admin/entitlements"),
                new Route("GET", "admin/entitlements/{entitlementId}"),
                new Route("PUT", "admin/entitlements/{entitlementId}"),
                new Route("POST", "requests"),
                new Route("GET", "mine"),
                new Route("GET", "admin/capabilities"),
                new Route("GET", "admin/events"),
                new Route("GET", "admin/requests/{requestId}"),
                new Route("GET", "admin/notification-deliveries"),
                new Route("GET", "admin/provisioning-failures"),
                new Route("GET", "admin/notification-deliveries/summary"),
                new Route("POST", "admin/notification-deliveries/{deliveryId}/retry"),
                new Route("POST", "admin/requests/{requestId}/provisioning/retry"),
                new Route("POST", "admin/requests/{requestId}/provisioning/close"),
                new Route("GET", "admin/references"),
                new Route("GET", "mine/{requestId}"),
                new Route("GET", "pending"),
                new Route("GET", "capabilities"),
                new Route("POST", "{requestId}/cancel"),
                new Route("POST", "{requestId}/approve"),
                new Route("POST", "{requestId}/reject")), routes);
        assertEquals(24, Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Path.class)).count());
    }

    private static Route route(Method method) {
        String verb;
        if (method.isAnnotationPresent(GET.class)) {
            verb = "GET";
        } else if (method.isAnnotationPresent(POST.class)) {
            verb = "POST";
        } else if (method.isAnnotationPresent(PUT.class)) {
            verb = "PUT";
        } else if (method.isAnnotationPresent(OPTIONS.class)) {
            verb = "OPTIONS";
        } else {
            throw new AssertionError("Route has no expected HTTP method: " + method.getName());
        }
        return new Route(verb, method.getAnnotation(Path.class).value());
    }

    private record Route(String method, String path) {
    }
}
