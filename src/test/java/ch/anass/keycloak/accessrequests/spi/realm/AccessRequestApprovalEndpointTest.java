package ch.anass.keycloak.accessrequests.spi.realm;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
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

class AccessRequestApprovalEndpointTest {

    private static final Class<?> RESOURCE_TYPE = AccessRequestRealmResource.class;
    private static final String DECISION_SUBMISSION_TYPE =
            "ch.anass.keycloak.accessrequests.spi.realm.AccessRequestRealmResource$DecisionSubmission";

    @Test
    void exposesAJsonPostHandlerForRequestApproval() throws Exception {
        Method approveHandler = handler("approveRequest");

        assertTrue(approveHandler.isAnnotationPresent(POST.class));
        assertEquals("{requestId}/approve", approveHandler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, approveHandler.getAnnotation(Consumes.class).value()[0]);
        assertEquals(MediaType.APPLICATION_JSON, approveHandler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, approveHandler.getReturnType());
        assertRequestIdAndDecisionSubmission(approveHandler);
    }

    @Test
    void exposesAJsonPostHandlerForRequestRejection() throws Exception {
        Method rejectHandler = handler("rejectRequest");

        assertTrue(rejectHandler.isAnnotationPresent(POST.class));
        assertEquals("{requestId}/reject", rejectHandler.getAnnotation(Path.class).value());
        assertEquals(MediaType.APPLICATION_JSON, rejectHandler.getAnnotation(Consumes.class).value()[0]);
        assertEquals(MediaType.APPLICATION_JSON, rejectHandler.getAnnotation(Produces.class).value()[0]);
        assertEquals(Response.class, rejectHandler.getReturnType());
        assertRequestIdAndDecisionSubmission(rejectHandler);
    }

    @Test
    void usesOneJsonDecisionPayloadForApprovalAndRejection() throws Exception {
        Class<?> decisionSubmission = Class.forName(DECISION_SUBMISSION_TYPE);

        assertTrue(decisionSubmission.isRecord());
        assertArrayEquals(
                new String[]{"comment"},
                Arrays.stream(decisionSubmission.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
        assertEquals(String.class, decisionSubmission.getRecordComponents()[0].getType());
    }

    private static Method handler(String name) {
        return Arrays.stream(RESOURCE_TYPE.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The access request approval API must expose a " + name + " handler."));
    }

    private static void assertRequestIdAndDecisionSubmission(Method handler) throws Exception {
        assertEquals(2, handler.getParameterCount());
        assertEquals(String.class, handler.getParameterTypes()[0]);
        assertEquals(
                "requestId",
                handler.getParameters()[0].getAnnotation(PathParam.class).value());
        assertEquals(Class.forName(DECISION_SUBMISSION_TYPE), handler.getParameterTypes()[1]);
    }
}
