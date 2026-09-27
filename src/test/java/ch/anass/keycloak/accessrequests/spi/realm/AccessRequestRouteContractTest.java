package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestRealmResource;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessRequestRouteContractTest {

    @Test
    void keepsThePublicRealmHttpContract() {
        Method[] methods = AccessRequestRealmResource.class.getDeclaredMethods();
        Set<String> routes = Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(Path.class))
                .map(AccessRequestRouteContractTest::route)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "GET catalog | consumes=- | produces=application/json | params=query:page=0,query:riskLevel,"
                        + "query:search,query:size=20,query:type",
                "OPTIONS catalog | consumes=- | produces=- | params=-",
                "GET admin/entitlements | consumes=- | produces=application/json | params=query:page=0,query:size=20",
                "POST admin/entitlements | consumes=application/json | produces=application/json | params=body",
                "GET admin/entitlements/{entitlementId} | consumes=- | produces=application/json | "
                        + "params=path:entitlementId",
                "PUT admin/entitlements/{entitlementId} | consumes=application/json | "
                        + "produces=application/json | params=body,path:entitlementId",
                "POST requests | consumes=application/json | produces=application/json | params=body",
                "GET mine | consumes=- | produces=application/json | params=query:from,query:page=0,"
                        + "query:resourceType,query:size=20,query:status,query:to",
                "GET admin/capabilities | consumes=- | produces=application/json | params=-",
                "GET admin/events | consumes=- | produces=application/json | params=query:actorId,query:from,"
                        + "query:page=0,query:requestId,query:size=20,query:to,query:type",
                "GET admin/requests/{requestId} | consumes=- | produces=application/json | "
                        + "params=path:requestId,query:historyPage=0,query:historySize=20",
                "GET admin/notification-deliveries | consumes=- | produces=application/json | "
                        + "params=query:page=0,query:size=20",
                "GET admin/provisioning-failures | consumes=- | produces=application/json | "
                        + "params=query:page=0,query:size=20,query:state=OPEN",
                "GET admin/notification-deliveries/summary | consumes=- | produces=application/json | params=-",
                "POST admin/notification-deliveries/{deliveryId}/retry | consumes=- | produces=- | "
                        + "params=path:deliveryId",
                "POST admin/requests/{requestId}/provisioning/retry | consumes=- | "
                        + "produces=application/json | params=path:requestId",
                "POST admin/requests/{requestId}/provisioning/close | consumes=application/json | "
                        + "produces=application/json | params=body,path:requestId",
                "GET admin/references | consumes=- | produces=application/json | "
                        + "params=query:max=50,query:search,query:type",
                "GET mine/{requestId} | consumes=- | produces=application/json | params=path:requestId",
                "GET pending | consumes=- | produces=application/json | params=query:page=0,query:size=20",
                "GET capabilities | consumes=- | produces=application/json | params=-",
                "POST {requestId}/cancel | consumes=- | produces=application/json | params=path:requestId",
                "POST {requestId}/approve | consumes=application/json | produces=application/json | "
                        + "params=body,path:requestId",
                "POST {requestId}/reject | consumes=application/json | produces=application/json | "
                        + "params=body,path:requestId"), routes);
        assertEquals(24, routes.size());
        assertEquals(24, Arrays.stream(methods).filter(method -> method.isAnnotationPresent(Path.class)).count());
    }

    private static String route(Method method) {
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
        String parameters = Arrays.stream(method.getParameters())
                .map(AccessRequestRouteContractTest::parameter)
                .sorted()
                .collect(Collectors.joining(","));
        return "%s %s | consumes=%s | produces=%s | params=%s".formatted(
                verb, method.getAnnotation(Path.class).value(),
                mediaTypes(method.getAnnotation(Consumes.class)),
                mediaTypes(method.getAnnotation(Produces.class)),
                parameters.isEmpty() ? "-" : parameters);
    }

    private static String parameter(Parameter parameter) {
        QueryParam query = parameter.getAnnotation(QueryParam.class);
        PathParam path = parameter.getAnnotation(PathParam.class);
        DefaultValue defaultValue = parameter.getAnnotation(DefaultValue.class);
        String source = query != null ? "query:" + query.value()
                : path != null ? "path:" + path.value() : "body";
        return source + (defaultValue == null ? "" : "=" + defaultValue.value());
    }

    private static String mediaTypes(Consumes annotation) {
        return annotation == null ? "-" : String.join(",", Arrays.stream(annotation.value()).sorted().toList());
    }

    private static String mediaTypes(Produces annotation) {
        return annotation == null ? "-" : String.join(",", Arrays.stream(annotation.value()).sorted().toList());
    }
}
