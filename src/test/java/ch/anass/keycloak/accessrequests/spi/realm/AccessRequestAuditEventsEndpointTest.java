package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningFailureCode;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestAuditEventsEndpointTest {

    @Test
    void exposesRealmScopedAdministrativeEventSearchWithEveryRequiredFilterAndPagination() {
        Method handler = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GET.class))
                .filter(method -> method.isAnnotationPresent(Path.class))
                .filter(method -> "admin/events".equals(method.getAnnotation(Path.class).value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The Admin Console needs GET admin/events."));

        assertEquals(Response.class, handler.getReturnType());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        Map<String, Class<?>> parameters = Arrays.stream(handler.getParameters())
                .collect(Collectors.toMap(parameter -> {
                    QueryParam query = parameter.getAnnotation(QueryParam.class);
                    assertNotNull(query, "Audit search must use named query parameters.");
                    return query.value();
                }, java.lang.reflect.Parameter::getType));
        assertEquals(Map.of(
                "from", String.class,
                "to", String.class,
                "type", String.class,
                "actorId", String.class,
                "requestId", String.class,
                "page", int.class,
                "size", int.class), parameters);
        assertEquals("0", defaultValue(handler, "page"));
        assertEquals("20", defaultValue(handler, "size"));
    }

    @Test
    void returnsOnlySafeEventSummaryFieldsAndARealPagingEnvelope() throws Exception {
        Class<?> page = responseType("AuditEventListResponse");
        Class<?> event = responseType("AuditEventResponse");

        assertTrue(page.isRecord());
        assertEquals(java.util.List.of("items", "page", "size", "total"), fields(page));
        assertTrue(event.isRecord());
        assertEquals(java.util.List.of("id", "requestId", "type", "actorId", "occurredAt"), fields(event),
                "The list must not leak decision comments, closure reasons, or raw failure diagnostics.");
    }

    @Test
    void exposesAnAuthorizedRequestDetailTargetForEventLinks() {
        Method handler = Arrays.stream(AccessRequestRealmResource.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GET.class))
                .filter(method -> method.isAnnotationPresent(Path.class))
                .filter(method -> "admin/requests/{requestId}".equals(method.getAnnotation(Path.class).value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Audit event links need GET admin/requests/{requestId}."));

        assertEquals(Response.class, handler.getReturnType());
        assertEquals(MediaType.APPLICATION_JSON, handler.getAnnotation(Produces.class).value()[0]);
        assertEquals(1, handler.getParameterCount());
        assertEquals("requestId", handler.getParameters()[0].getAnnotation(PathParam.class).value());
    }

    @Test
    void usesAnAdminOnlyDetailEnvelopeWithRequesterStatusesAndHistoryActors() throws Exception {
        assertEquals(java.util.List.of("id", "requesterId", "entitlementId", "resourceType", "resourceName",
                "decisionStatus", "provisioningStatus", "createdAt", "provisioningClosedAt", "justification",
                "decision", "history"), fields(responseType("AdminRequestDetailResponse")));
        assertEquals(java.util.List.of("type", "actorId", "occurredAt", "failureCode", "closureReason"),
                fields(responseType("AdminRequestHistoryEntryResponse")));
        assertTrue(!fields(responseType("RequestDetailResponse")).contains("requesterId"));
        assertTrue(!fields(responseType("RequestHistoryEntryResponse")).contains("actorId"));
    }

    @Test
    void exposesOnlySafeFailureCodesAndClosureReasonsInAdministrativeHistory() {
        Instant occurredAt = Instant.parse("2026-09-24T10:00:00Z");
        var failure = AccessRequestRealmResource.AdminRequestHistoryEntryResponse.from(
                AccessRequestEvent.rehydrate("failed", "request", "realm",
                        AccessRequestEventType.PROVISIONING_FAILED, "approver", occurredAt,
                        "Internal JDBC password=secret", "RESOURCE_MISSING", 2L));
        assertEquals(ProvisioningFailureCode.RESOURCE_MISSING, failure.failureCode());
        assertNull(failure.closureReason());

        var unrecognized = AccessRequestRealmResource.AdminRequestHistoryEntryResponse.from(
                AccessRequestEvent.rehydrate("unknown", "request", "realm",
                        AccessRequestEventType.PROVISIONING_FAILED, "approver", occurredAt,
                        "Internal exception", "password=secret", 3L));
        assertEquals(ProvisioningFailureCode.UNKNOWN, unrecognized.failureCode());

        var closure = AccessRequestRealmResource.AdminRequestHistoryEntryResponse.from(
                AccessRequestEvent.rehydrate("closed", "request", "realm",
                        AccessRequestEventType.PROVISIONING_CLOSED, "manager", occurredAt,
                        "The role was permanently removed.", "unexpected metadata", 4L));
        assertNull(closure.failureCode());
        assertEquals("The role was permanently removed.", closure.closureReason());

        var approval = AccessRequestRealmResource.AdminRequestHistoryEntryResponse.from(
                AccessRequestEvent.rehydrate("approved", "request", "realm",
                        AccessRequestEventType.REQUEST_APPROVED, "approver", occurredAt,
                        "Decision comment", "RESOURCE_MISSING", 1L));
        assertNull(approval.failureCode());
        assertNull(approval.closureReason());
        assertTrue(!fields(AccessRequestRealmResource.AdminRequestHistoryEntryResponse.class).contains("comment"));
        assertTrue(!fields(AccessRequestRealmResource.AdminRequestHistoryEntryResponse.class).contains("metadata"));
    }

    private static String defaultValue(Method handler, String name) {
        return Arrays.stream(handler.getParameters())
                .filter(parameter -> name.equals(parameter.getAnnotation(QueryParam.class).value()))
                .findFirst()
                .orElseThrow()
                .getAnnotation(DefaultValue.class).value();
    }

    private static Class<?> responseType(String name) throws ClassNotFoundException {
        return Class.forName(AccessRequestRealmResource.class.getName() + "$" + name);
    }

    private static java.util.List<String> fields(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
