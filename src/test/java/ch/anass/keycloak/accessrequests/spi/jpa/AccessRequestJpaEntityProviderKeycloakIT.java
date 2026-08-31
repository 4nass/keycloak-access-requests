package ch.anass.keycloak.accessrequests.spi.jpa;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class AccessRequestJpaEntityProviderKeycloakIT {

    private static final String ACCESS_REQUESTS_API_AUDIENCE = "access-requests-api";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("keycloak")
            .withUsername("keycloak")
            .withPassword("keycloak")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @Test
    void appliesTheProviderChangelogAtKeycloakStartupAndKeepsItAppliedAfterRestart() throws Exception {
        try (GenericContainer<?> firstServer = keycloak()) {
            firstServer.start();
            assertProviderSchemaApplied();
            assertRealmEndpointExposed(firstServer);
            assertCatalogEndpointRequiresAuthenticationAndListsPublishedEntitlements(firstServer);
            assertRequestSubmissionRequiresAudienceAndCreatesAnAuditedPendingRequest(firstServer);
            assertRequesterCanListAndCancelOnlyOwnRequests(firstServer);
            assertEntitlementScopedApproversCanDecideRequests(firstServer);
        }

        try (GenericContainer<?> restartedServer = keycloak()) {
            restartedServer.start();
            assertProviderSchemaApplied();
            assertRealmEndpointExposed(restartedServer);
            assertCatalogEndpointRequiresAuthenticationAndListsPublishedEntitlements(restartedServer);
            assertRequestSubmissionRequiresAudienceAndCreatesAnAuditedPendingRequest(restartedServer);
            assertRequesterCanListAndCancelOnlyOwnRequests(restartedServer);
            assertEntitlementScopedApproversCanDecideRequests(restartedServer);
        }
    }

    private GenericContainer<?> keycloak() {
        Path providerJar = Path.of("target", "keycloak-access-requests.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(providerJar), "The provider JAR must be built before integration tests run.");

        return new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.4.0"))
                .withNetwork(NETWORK)
                .withEnv("KC_DB", "postgres")
                .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/keycloak")
                .withEnv("KC_DB_USERNAME", "keycloak")
                .withEnv("KC_DB_PASSWORD", "keycloak")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(providerJar),
                        "/opt/keycloak/providers/keycloak-access-requests.jar")
                .withCommand("start-dev", "--http-port=8080")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/master").forPort(8080).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private void assertProviderSchemaApplied() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertTrue(tableExists(connection, "ar_access_request"));
            assertTrue(tableExists(connection, "ar_access_request_history"));
            assertTrue(tableExists(connection, "ar_entitlement"));
            assertEquals(3, providerChangeSetCount(connection));
        }
    }

    private void assertRealmEndpointExposed(GenericContainer<?> server) throws Exception {
        URI endpoint = URI.create("http://%s:%d/realms/master/access-requests/catalog".formatted(
                server.getHost(), server.getMappedPort(8080)));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());

        assertEquals(204, response.statusCode());
        assertTrue(response.headers().firstValue("Allow")
                .map(allowedMethods -> allowedMethods.contains("GET") && allowedMethods.contains("OPTIONS"))
                .orElse(false));
    }

    private void assertCatalogEndpointRequiresAuthenticationAndListsPublishedEntitlements(
            GenericContainer<?> server) throws Exception {
        URI endpoint = URI.create("http://%s:%d/realms/master/access-requests/catalog"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> unauthorizedResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, unauthorizedResponse.statusCode());

        String adminToken = accessToken(server, "admin-cli");
        String allowedClientId = "catalog-trusted-" + UUID.randomUUID();
        createDirectAccessClient(server, adminToken, allowedClientId);
        String allowedClientInternalId = addAccessRequestsAudience(server, adminToken, allowedClientId);
        String allowedClientToken = accessToken(server, allowedClientId);
        assertTrue(hasAccessRequestsApiAudience(allowedClientToken));
        String untrustedClientId = "catalog-untrusted-" + UUID.randomUUID();
        createDirectAccessClient(server, allowedClientToken, untrustedClientId);
        HttpResponse<Void> forbiddenResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + accessToken(server, untrustedClientId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, forbiddenResponse.statusCode());

        String entitlementId = UUID.randomUUID().toString();
        insertPublishedEntitlement(entitlementId);

        HttpRequest authenticatedRequest = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + allowedClientToken)
                .GET()
                .build();
        HttpResponse<String> catalogResponse = HttpClient.newHttpClient().send(
                authenticatedRequest,
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, catalogResponse.statusCode());
        assertTrue(catalogResponse.body().contains("\"id\":\"" + entitlementId + "\""));
        assertTrue(catalogResponse.body().contains("\"type\":\"CLIENT_ROLE\""));
        assertTrue(catalogResponse.body().contains("\"name\":\"Finance Reader\""));
        assertTrue(catalogResponse.body().contains("\"riskLevel\":\"LOW\""));
        assertTrue(catalogResponse.body().contains("\"alreadyGranted\":false"));
        assertTrue(catalogResponse.body().contains("\"pendingRequest\":false"));

        HttpResponse<Void> invalidPaginationResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(endpoint + "?page=" + Integer.MAX_VALUE + "&size=2"))
                        .header("Authorization", "Bearer " + allowedClientToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(400, invalidPaginationResponse.statusCode());

        removeAccessRequestsAudience(server, adminToken, allowedClientInternalId);
        String revokedClientToken = accessToken(server, allowedClientId);
        assertFalse(hasAccessRequestsApiAudience(revokedClientToken));
        HttpResponse<Void> revokedClientResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + revokedClientToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, revokedClientResponse.statusCode());
    }

    private void assertRequesterCanListAndCancelOnlyOwnRequests(GenericContainer<?> server) throws Exception {
        URI accessRequestsEndpoint = URI.create("http://%s:%d/realms/master/access-requests"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        URI requestsEndpoint = URI.create(accessRequestsEndpoint + "/requests");
        URI myRequestsEndpoint = URI.create(accessRequestsEndpoint + "/mine");
        String adminToken = accessToken(server, "admin-cli");
        HttpResponse<Void> unauthenticatedListResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(myRequestsEndpoint).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, unauthenticatedListResponse.statusCode());
        HttpResponse<Void> wrongAudienceListResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(myRequestsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, wrongAudienceListResponse.statusCode());
        String clientId = "request-manager-" + UUID.randomUUID();
        createDirectAccessClient(server, adminToken, clientId);
        addAccessRequestsAudience(server, adminToken, clientId);
        String requesterUsername = "requester-" + UUID.randomUUID();
        String requesterPassword = "requester-password";
        createEnabledUser(server, adminToken, requesterUsername, requesterPassword);
        String requesterToken = accessToken(server, clientId, requesterUsername, requesterPassword);

        String firstEntitlementId = UUID.randomUUID().toString();
        String secondEntitlementId = UUID.randomUUID().toString();
        insertPublishedEntitlement(firstEntitlementId);
        insertPublishedEntitlement(secondEntitlementId);
        HttpResponse<String> firstCreatedResponse = HttpClient.newHttpClient().send(
                requestSubmission(
                        requestsEndpoint,
                        requesterToken,
                        firstEntitlementId,
                        "I need access to the first Finance Portal report."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, firstCreatedResponse.statusCode());
        String firstRequestId = responseId(firstCreatedResponse.body());
        HttpResponse<String> secondCreatedResponse = HttpClient.newHttpClient().send(
                requestSubmission(
                        requestsEndpoint,
                        requesterToken,
                        secondEntitlementId,
                        "I need access to the second Finance Portal report."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, secondCreatedResponse.statusCode());
        String secondRequestId = responseId(secondCreatedResponse.body());

        HttpResponse<String> firstPageResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?page=0&size=1"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, firstPageResponse.statusCode());
        assertRequestPage(firstPageResponse.body(), 0, 1, 2, firstRequestId, secondRequestId);

        HttpResponse<String> secondPageResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?page=1&size=1"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, secondPageResponse.statusCode());
        assertRequestPage(secondPageResponse.body(), 1, 1, 2, firstRequestId, secondRequestId);
        assertFalse(firstPageResponse.body().equals(secondPageResponse.body()));

        HttpResponse<String> invalidPaginationResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?page=" + Integer.MAX_VALUE + "&size=2"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidPaginationResponse.statusCode());
        assertError(invalidPaginationResponse.body(), "INVALID_REQUEST_QUERY", null);

        HttpResponse<String> excessiveOffsetResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?page=101&size=100"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, excessiveOffsetResponse.statusCode());
        assertError(excessiveOffsetResponse.body(), "INVALID_REQUEST_QUERY", null);

        HttpResponse<String> invalidFilterResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?status=UNKNOWN"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidFilterResponse.statusCode());
        assertError(invalidFilterResponse.body(), "INVALID_REQUEST_QUERY", null);

        HttpResponse<String> resourceTypeFilterResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?resourceType=GROUP"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resourceTypeFilterResponse.statusCode());
        assertTrue(resourceTypeFilterResponse.body().contains("\"total\":0"));

        HttpResponse<String> fromFilterResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?from=2100-01-01T00:00:00Z"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, fromFilterResponse.statusCode());
        assertTrue(fromFilterResponse.body().contains("\"total\":0"));

        HttpResponse<String> toFilterResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?to=1970-01-01T00:00:00Z"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, toFilterResponse.statusCode());
        assertTrue(toFilterResponse.body().contains("\"total\":0"));

        String otherUsername = "other-requester-" + UUID.randomUUID();
        String otherPassword = "other-requester-password";
        createEnabledUser(server, adminToken, otherUsername, otherPassword);
        String otherRequesterToken = accessToken(server, clientId, otherUsername, otherPassword);
        HttpResponse<String> otherRequesterListResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(myRequestsEndpoint)
                        .header("Authorization", "Bearer " + otherRequesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, otherRequesterListResponse.statusCode());
        assertTrue(otherRequesterListResponse.body().contains("\"total\":0"));
        assertFalse(otherRequesterListResponse.body().contains(firstRequestId));
        assertFalse(otherRequesterListResponse.body().contains(secondRequestId));

        HttpResponse<String> unauthorizedCancellationResponse = HttpClient.newHttpClient().send(
                requestCancellation(accessRequestsEndpoint, otherRequesterToken, firstRequestId),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, unauthorizedCancellationResponse.statusCode());
        assertError(unauthorizedCancellationResponse.body(), "REQUEST_CANCELLATION_FORBIDDEN", firstRequestId);

        String unknownRequestId = UUID.randomUUID().toString();
        HttpResponse<String> unknownRequestResponse = HttpClient.newHttpClient().send(
                requestCancellation(accessRequestsEndpoint, requesterToken, unknownRequestId),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, unknownRequestResponse.statusCode());
        assertError(unknownRequestResponse.body(), "REQUEST_NOT_FOUND", unknownRequestId);

        String requestFromAnotherRealm = UUID.randomUUID().toString();
        insertPendingRequestFromAnotherRealm(requestFromAnotherRealm);
        HttpResponse<String> otherRealmRequestResponse = HttpClient.newHttpClient().send(
                requestCancellation(accessRequestsEndpoint, requesterToken, requestFromAnotherRealm),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, otherRealmRequestResponse.statusCode());
        assertError(otherRealmRequestResponse.body(), "REQUEST_NOT_FOUND", requestFromAnotherRealm);

        HttpResponse<Void> canceledResponse = HttpClient.newHttpClient().send(
                requestCancellation(accessRequestsEndpoint, requesterToken, firstRequestId),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, canceledResponse.statusCode());

        HttpResponse<String> canceledRequestsResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "?status=CANCELED"))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, canceledRequestsResponse.statusCode());
        assertTrue(canceledRequestsResponse.body().contains(firstRequestId));
        assertFalse(canceledRequestsResponse.body().contains(secondRequestId));

        HttpResponse<String> terminalRequestResponse = HttpClient.newHttpClient().send(
                requestCancellation(accessRequestsEndpoint, requesterToken, firstRequestId),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, terminalRequestResponse.statusCode());
        assertError(terminalRequestResponse.body(), "INVALID_REQUEST_STATE", firstRequestId);
    }

    private void assertEntitlementScopedApproversCanDecideRequests(GenericContainer<?> server) throws Exception {
        URI accessRequestsEndpoint = URI.create("http://%s:%d/realms/master/access-requests"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        URI requestsEndpoint = URI.create(accessRequestsEndpoint + "/requests");
        String adminToken = accessToken(server, "admin-cli");
        String clientId = "request-approver-" + UUID.randomUUID();
        createDirectAccessClient(server, adminToken, clientId);
        addAccessRequestsAudience(server, adminToken, clientId);

        String requesterUsername = "approval-requester-" + UUID.randomUUID();
        String requesterPassword = "requester-password";
        createEnabledUser(server, adminToken, requesterUsername, requesterPassword);
        String requesterToken = accessToken(server, clientId, requesterUsername, requesterPassword);

        String rejectedRequesterUsername = "rejection-requester-" + UUID.randomUUID();
        String rejectedRequesterPassword = "rejection-requester-password";
        createEnabledUser(server, adminToken, rejectedRequesterUsername, rejectedRequesterPassword);
        String rejectedRequesterToken = accessToken(
                server, clientId, rejectedRequesterUsername, rejectedRequesterPassword);

        String approverUsername = "finance-approver-" + UUID.randomUUID();
        String approverPassword = "approver-password";
        createEnabledUser(server, adminToken, approverUsername, approverPassword);
        String approverToken = accessToken(server, clientId, approverUsername, approverPassword);
        String approverId = subjectOf(approverToken);
        String approverRoleId = createRealmRoleAndAssignToUser(
                server, adminToken, approverId, "finance-approver-" + UUID.randomUUID());

        String unauthorizedUsername = "unauthorized-approver-" + UUID.randomUUID();
        String unauthorizedPassword = "unauthorized-password";
        createEnabledUser(server, adminToken, unauthorizedUsername, unauthorizedPassword);
        String unauthorizedToken = accessToken(server, clientId, unauthorizedUsername, unauthorizedPassword);

        String entitlementId = UUID.randomUUID().toString();
        insertPublishedEntitlement(entitlementId, approverRoleId);

        HttpResponse<String> approvedRequestResponse = HttpClient.newHttpClient().send(
                requestSubmission(
                        requestsEndpoint,
                        requesterToken,
                        entitlementId,
                        "I need access to Finance Portal reports for the project."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, approvedRequestResponse.statusCode());
        String approvedRequestId = responseId(approvedRequestResponse.body());

        HttpResponse<String> selfApprovalResponse = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, requesterToken, approvedRequestId, "approve", "Approved."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, selfApprovalResponse.statusCode());
        assertError(selfApprovalResponse.body(), "SELF_APPROVAL_FORBIDDEN", approvedRequestId);

        HttpResponse<String> unauthorizedApprovalResponse = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, unauthorizedToken, approvedRequestId, "approve", "Approved."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, unauthorizedApprovalResponse.statusCode());
        assertError(unauthorizedApprovalResponse.body(), "NOT_AUTHORIZED_APPROVER", approvedRequestId);

        String approvalComment = "Approved for the Finance Portal project.";
        HttpResponse<String> approvalResponse = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, approvedRequestId, "approve", approvalComment),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approvalResponse.statusCode());
        assertTrue(approvalResponse.body().contains("\"id\":\"" + approvedRequestId + "\""));
        assertTrue(approvalResponse.body().contains("\"decisionStatus\":\"APPROVED\""));
        assertDecisionAndAuditEvent(
                approvedRequestId, "APPROVED", "REQUEST_APPROVED", approverId, approvalComment);

        HttpResponse<String> repeatedDecisionResponse = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, approvedRequestId, "reject", "Too late."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, repeatedDecisionResponse.statusCode());
        assertError(repeatedDecisionResponse.body(), "INVALID_REQUEST_STATE", approvedRequestId);

        HttpResponse<String> rejectedRequestResponse = HttpClient.newHttpClient().send(
                requestSubmission(
                        requestsEndpoint,
                        rejectedRequesterToken,
                        entitlementId,
                        "I need access to Finance Portal reports for another project."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, rejectedRequestResponse.statusCode());
        String rejectedRequestId = responseId(rejectedRequestResponse.body());

        String rejectionComment = "The requested access is not justified.";
        HttpResponse<String> rejectionResponse = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, rejectedRequestId, "reject", rejectionComment),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rejectionResponse.statusCode());
        assertTrue(rejectionResponse.body().contains("\"id\":\"" + rejectedRequestId + "\""));
        assertTrue(rejectionResponse.body().contains("\"decisionStatus\":\"REJECTED\""));
        assertDecisionAndAuditEvent(
                rejectedRequestId, "REJECTED", "REQUEST_REJECTED", approverId, rejectionComment);
    }

    private void assertRequestPage(
            String body, int page, int size, long total, String firstRequestId, String secondRequestId) {
        assertTrue(body.contains("\"page\":" + page));
        assertTrue(body.contains("\"size\":" + size));
        assertTrue(body.contains("\"total\":" + total));
        int requestCount = (body.contains(firstRequestId) ? 1 : 0) + (body.contains(secondRequestId) ? 1 : 0);
        assertEquals(1, requestCount, "A page of size one must contain exactly one request.");
        var createdAtMatcher = Pattern.compile("\\\"createdAt\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(createdAtMatcher.find(), "Each request summary must expose its creation timestamp.");
        Instant.parse(createdAtMatcher.group(1));
    }

    private void assertError(String body, String code, String requestId) {
        assertTrue(body.contains("\"code\":\"" + code + "\""));
        if (requestId != null) {
            assertTrue(body.contains("\"requestId\":\"" + requestId + "\""));
        }
    }

    private HttpRequest requestCancellation(URI accessRequestsEndpoint, String accessToken, String requestId) {
        return HttpRequest.newBuilder(URI.create(accessRequestsEndpoint + "/" + requestId + "/cancel"))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private HttpRequest requestDecision(
            URI accessRequestsEndpoint,
            String accessToken,
            String requestId,
            String decision,
            String comment) {
        return HttpRequest.newBuilder(URI.create(accessRequestsEndpoint + "/" + requestId + "/" + decision))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"comment":"%s"}
                        """.formatted(comment)))
                .build();
    }

    private void assertRequestSubmissionRequiresAudienceAndCreatesAnAuditedPendingRequest(
            GenericContainer<?> server) throws Exception {
        URI endpoint = URI.create("http://%s:%d/realms/master/access-requests/requests"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        String adminToken = accessToken(server, "admin-cli");

        HttpResponse<Void> unauthenticatedResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, unauthenticatedResponse.statusCode());
        assertFalse(hasAccessRequestsApiAudience(adminToken));

        HttpResponse<Void> wrongAudienceResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, adminToken, UUID.randomUUID().toString(), "Need access to finance data."),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, wrongAudienceResponse.statusCode());

        String clientId = "request-submitter-" + UUID.randomUUID();
        createDirectAccessClient(server, adminToken, clientId);
        addAccessRequestsAudience(server, adminToken, clientId);
        String accessToken = accessToken(server, clientId);

        HttpResponse<Void> missingEntitlementResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, "{\"justification\":\"Need access to finance data.\"}"),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(400, missingEntitlementResponse.statusCode());

        HttpResponse<Void> missingJustificationResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, "{\"entitlementId\":\"" + UUID.randomUUID() + "\"}"),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(400, missingJustificationResponse.statusCode());

        HttpResponse<Void> unknownEntitlementResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, UUID.randomUUID().toString(), "Need access to finance data."),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(404, unknownEntitlementResponse.statusCode());

        String nonRequestableEntitlementId = UUID.randomUUID().toString();
        insertEntitlement(nonRequestableEntitlementId, "CLIENT_ROLE", "unpublished-role-" + nonRequestableEntitlementId,
                false);
        HttpResponse<Void> nonRequestableResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, nonRequestableEntitlementId, "Need access to finance data."),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(409, nonRequestableResponse.statusCode());

        String roleName = "already-granted-" + UUID.randomUUID();
        String roleId = createRealmRoleAndAssignToUser(server, adminToken, subjectOf(accessToken), roleName);
        String alreadyGrantedEntitlementId = UUID.randomUUID().toString();
        insertEntitlement(alreadyGrantedEntitlementId, "REALM_ROLE", roleId, true);
        String refreshedAccessToken = accessToken(server, clientId);
        HttpResponse<Void> alreadyGrantedResponse = HttpClient.newHttpClient().send(
                requestSubmission(
                        endpoint, refreshedAccessToken, alreadyGrantedEntitlementId, "Need access to finance data."),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(409, alreadyGrantedResponse.statusCode());

        String entitlementId = UUID.randomUUID().toString();
        String justification = "I need read-only access to Finance Portal reports.";
        insertPublishedEntitlement(entitlementId);

        HttpResponse<String> createdResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, entitlementId, justification),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(201, createdResponse.statusCode());
        String requestId = responseId(createdResponse.body());
        assertTrue(createdResponse.body().contains("\"entitlementId\":\"" + entitlementId + "\""));
        assertTrue(createdResponse.body().contains("\"decisionStatus\":\"PENDING\""));
        assertTrue(createdResponse.body().contains("\"provisioningStatus\":\"NOT_STARTED\""));
        assertPendingRequestAndCreatedAuditEvent(
                requestId, entitlementId, subjectOf(accessToken), justification);

        HttpResponse<Void> duplicateResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, entitlementId, justification),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(409, duplicateResponse.statusCode());

        HttpResponse<Void> invalidPayloadResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, entitlementId, ""),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(400, invalidPayloadResponse.statusCode());
    }

    private HttpRequest requestSubmission(URI endpoint, String accessToken, String entitlementId, String justification) {
        return requestSubmission(endpoint, accessToken, """
                {"entitlementId":"%s","justification":"%s"}
                """.formatted(entitlementId, justification));
    }

    private HttpRequest requestSubmission(URI endpoint, String accessToken, String jsonBody) {
        return HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    private String responseId(String response) {
        var matcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
        assertTrue(matcher.find(), "The created request response must contain an identifier.");
        return matcher.group(1);
    }

    private String subjectOf(String accessToken) {
        String[] segments = accessToken.split("\\.");
        assertEquals(3, segments.length, "The access token must be a JWT.");
        String payload = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\\\"sub\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(payload);
        assertTrue(matcher.find(), "The access token payload must contain a subject.");
        return matcher.group(1);
    }

    private void assertPendingRequestAndCreatedAuditEvent(
            String requestId, String entitlementId, String requesterId, String justification) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var requestStatement = connection.prepareStatement("""
                     select REQUESTER_ID, JUSTIFICATION, DECISION_STATUS, PROVISIONING_STATUS
                       from AR_ACCESS_REQUEST
                      where ID = ?
                        and ENTITLEMENT_ID = ?
                     """)) {
            requestStatement.setString(1, requestId);
            requestStatement.setString(2, entitlementId);
            try (ResultSet result = requestStatement.executeQuery()) {
                assertTrue(result.next(), "The submitted request must be persisted.");
                assertEquals(requesterId, result.getString("REQUESTER_ID"));
                assertEquals(justification, result.getString("JUSTIFICATION"));
                assertEquals("PENDING", result.getString("DECISION_STATUS"));
                assertEquals("NOT_STARTED", result.getString("PROVISIONING_STATUS"));
                assertFalse(result.next(), "Exactly one submitted request must be persisted.");
            }

            try (var eventStatement = connection.prepareStatement("""
                    select EVENT_TYPE, ACTOR_ID
                      from AR_ACCESS_REQUEST_HISTORY
                     where REQUEST_ID = ?
                    """)) {
                eventStatement.setString(1, requestId);
                try (ResultSet result = eventStatement.executeQuery()) {
                    assertTrue(result.next(), "Creating a request must persist its audit event.");
                    assertEquals("REQUEST_CREATED", result.getString("EVENT_TYPE"));
                    assertEquals(requesterId, result.getString("ACTOR_ID"));
                    assertFalse(result.next(), "Exactly one creation audit event must be persisted.");
                }
            }
        }
    }

    private void assertDecisionAndAuditEvent(
            String requestId,
            String decisionStatus,
            String eventType,
            String approverId,
            String comment) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var requestStatement = connection.prepareStatement("""
                     select DECISION_STATUS, APPROVER_ID, DECISION_COMMENT
                       from AR_ACCESS_REQUEST
                      where ID = ?
                     """)) {
            requestStatement.setString(1, requestId);
            try (ResultSet result = requestStatement.executeQuery()) {
                assertTrue(result.next(), "The decided request must be persisted.");
                assertEquals(decisionStatus, result.getString("DECISION_STATUS"));
                assertEquals(approverId, result.getString("APPROVER_ID"));
                assertEquals(comment, result.getString("DECISION_COMMENT"));
                assertFalse(result.next(), "Exactly one decided request must be persisted.");
            }

            try (var eventStatement = connection.prepareStatement("""
                    select EVENT_TYPE, ACTOR_ID, COMMENT
                      from AR_ACCESS_REQUEST_HISTORY
                     where REQUEST_ID = ?
                       and EVENT_TYPE = ?
                    """)) {
                eventStatement.setString(1, requestId);
                eventStatement.setString(2, eventType);
                try (ResultSet result = eventStatement.executeQuery()) {
                    assertTrue(result.next(), "Deciding a request must persist its audit event.");
                    assertEquals(eventType, result.getString("EVENT_TYPE"));
                    assertEquals(approverId, result.getString("ACTOR_ID"));
                    assertEquals(comment, result.getString("COMMENT"));
                    assertFalse(result.next(), "Exactly one decision audit event must be persisted.");
                }
            }
        }
    }

    private String accessToken(GenericContainer<?> server, String clientId) throws Exception {
        return accessToken(server, clientId, "admin", "admin");
    }

    private String accessToken(GenericContainer<?> server, String clientId, String username, String password) throws Exception {
        URI tokenEndpoint = URI.create("http://%s:%d/realms/master/protocol/openid-connect/token"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=password&client_id=%s&username=%s&password=%s"
                                .formatted(clientId, username, password)))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        var matcher = Pattern.compile("\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(response.body());
        assertTrue(matcher.find(), "The token response must contain an access token.");
        return matcher.group(1);
    }

    private void createEnabledUser(
            GenericContainer<?> server, String adminToken, String username, String password) throws Exception {
        URI usersEndpoint = URI.create("http://%s:%d/admin/realms/master/users"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(usersEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "username":"%s",
                                  "enabled":true,
                                  "credentials":[{"type":"password","value":"%s","temporary":false}]
                                }
                                """.formatted(username, password)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode());
    }

    private void createDirectAccessClient(GenericContainer<?> server, String adminToken, String clientId)
            throws Exception {
        URI clientsEndpoint = URI.create("http://%s:%d/admin/realms/master/clients"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpRequest request = HttpRequest.newBuilder(clientsEndpoint)
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"clientId":"%s","enabled":true,"publicClient":true,"directAccessGrantsEnabled":true}
                        """.formatted(clientId)))
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode());
    }

    private String addAccessRequestsAudience(GenericContainer<?> server, String adminToken, String clientId)
            throws Exception {
        ensureAccessRequestsApiClient(server, adminToken);
        String clientScopeId = ensureAccessRequestsApiClientScope(server, adminToken);
        String clientInternalId = clientInternalId(server, adminToken, clientId);

        URI defaultScopeEndpoint = URI.create("http://%s:%d/admin/realms/master/clients/%s/default-client-scopes/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), clientInternalId, clientScopeId));
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(defaultScopeEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
        return clientInternalId;
    }

    private void removeAccessRequestsAudience(GenericContainer<?> server, String adminToken, String clientInternalId)
            throws Exception {
        String clientScopeId = findAccessRequestsApiClientScopeId(server, adminToken);
        URI defaultScopeEndpoint = URI.create("http://%s:%d/admin/realms/master/clients/%s/default-client-scopes/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), clientInternalId, clientScopeId));
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(defaultScopeEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private String ensureAccessRequestsApiClientScope(GenericContainer<?> server, String adminToken) throws Exception {
        String existingScopeId = findAccessRequestsApiClientScopeIdOrNull(server, adminToken);
        if (existingScopeId != null) {
            return existingScopeId;
        }

        URI clientScopesEndpoint = URI.create("http://%s:%d/admin/realms/master/client-scopes"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> createScopeResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(clientScopesEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"%s","protocol":"openid-connect"}
                                """.formatted(ACCESS_REQUESTS_API_AUDIENCE)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, createScopeResponse.statusCode());

        String clientScopeId = findAccessRequestsApiClientScopeId(server, adminToken);
        URI mapperEndpoint = URI.create("http://%s:%d/admin/realms/master/client-scopes/%s/protocol-mappers/models"
                .formatted(server.getHost(), server.getMappedPort(8080), clientScopeId));
        HttpRequest mapperRequest = HttpRequest.newBuilder(mapperEndpoint)
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "name":"access-requests-api-%s",
                          "protocol":"openid-connect",
                          "protocolMapper":"oidc-audience-mapper",
                          "config":{
                            "included.client.audience":"%s",
                            "access.token.claim":"true",
                            "id.token.claim":"false",
                            "introspection.token.claim":"true"
                          }
                        }
                        """.formatted(UUID.randomUUID(), ACCESS_REQUESTS_API_AUDIENCE)))
                .build();
        HttpResponse<Void> mapperResponse = HttpClient.newHttpClient().send(
                mapperRequest, HttpResponse.BodyHandlers.discarding());
        assertEquals(201, mapperResponse.statusCode());
        return clientScopeId;
    }

    private String clientInternalId(GenericContainer<?> server, String adminToken, String clientId) throws Exception {
        URI clientEndpoint = URI.create("http://%s:%d/admin/realms/master/clients?clientId=%s"
                .formatted(server.getHost(), server.getMappedPort(8080), clientId));
        HttpResponse<String> clientResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(clientEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, clientResponse.statusCode());
        var clientIdMatcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(clientResponse.body());
        assertTrue(clientIdMatcher.find(), "The configured client must have an internal identifier.");
        return clientIdMatcher.group(1);
    }

    private String findAccessRequestsApiClientScopeId(GenericContainer<?> server, String adminToken) throws Exception {
        String clientScopeId = findAccessRequestsApiClientScopeIdOrNull(server, adminToken);
        assertTrue(clientScopeId != null, "The access requests client scope must exist.");
        return clientScopeId;
    }

    private String findAccessRequestsApiClientScopeIdOrNull(GenericContainer<?> server, String adminToken)
            throws Exception {
        URI clientScopesEndpoint = URI.create("http://%s:%d/admin/realms/master/client-scopes?search=%s"
                .formatted(server.getHost(), server.getMappedPort(8080), ACCESS_REQUESTS_API_AUDIENCE));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(clientScopesEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var scopeIdMatcher = Pattern.compile(
                        "\\{[^{}]*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^{}]*\\\"name\\\"\\s*:\\s*\\\""
                                + ACCESS_REQUESTS_API_AUDIENCE + "\\\"")
                .matcher(response.body());
        return scopeIdMatcher.find() ? scopeIdMatcher.group(1) : null;
    }

    private void ensureAccessRequestsApiClient(GenericContainer<?> server, String adminToken) throws Exception {
        URI clientEndpoint = URI.create("http://%s:%d/admin/realms/master/clients?clientId=%s"
                .formatted(server.getHost(), server.getMappedPort(8080), ACCESS_REQUESTS_API_AUDIENCE));
        HttpResponse<String> existingClientResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(clientEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, existingClientResponse.statusCode());
        if (existingClientResponse.body().contains("\"clientId\":\"" + ACCESS_REQUESTS_API_AUDIENCE + "\"")) {
            return;
        }

        URI clientsEndpoint = URI.create("http://%s:%d/admin/realms/master/clients"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpRequest createClientRequest = HttpRequest.newBuilder(clientsEndpoint)
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "clientId":"%s",
                          "enabled":true,
                          "protocol":"openid-connect",
                          "standardFlowEnabled":false,
                          "directAccessGrantsEnabled":false,
                          "serviceAccountsEnabled":false
                        }
                        """.formatted(ACCESS_REQUESTS_API_AUDIENCE)))
                .build();
        HttpResponse<Void> createClientResponse = HttpClient.newHttpClient().send(
                createClientRequest, HttpResponse.BodyHandlers.discarding());
        assertEquals(201, createClientResponse.statusCode());
    }

    private String createRealmRoleAndAssignToUser(
            GenericContainer<?> server, String adminToken, String userId, String roleName) throws Exception {
        URI rolesEndpoint = URI.create("http://%s:%d/admin/realms/master/roles"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> createRoleResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(rolesEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"%s"}
                                """.formatted(roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, createRoleResponse.statusCode());

        URI roleEndpoint = URI.create("%s/%s".formatted(rolesEndpoint, roleName));
        HttpResponse<String> roleResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(roleEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, roleResponse.statusCode());
        var roleIdMatcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(roleResponse.body());
        assertTrue(roleIdMatcher.find(), "The created realm role must have an identifier.");
        String roleId = roleIdMatcher.group(1);

        URI roleMappingsEndpoint = URI.create("http://%s:%d/admin/realms/master/users/%s/role-mappings/realm"
                .formatted(server.getHost(), server.getMappedPort(8080), userId));
        HttpResponse<Void> assignRoleResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(roleMappingsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                [{"id":"%s","name":"%s"}]
                                """.formatted(roleId, roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, assignRoleResponse.statusCode());
        return roleId;
    }

    private boolean hasAccessRequestsApiAudience(String accessToken) {
        String[] segments = accessToken.split("\\.");
        assertEquals(3, segments.length, "The access token must be a JWT.");
        String payload = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
        return Pattern.compile("\\\"aud\\\"\\s*:\\s*(?:\\\"" + ACCESS_REQUESTS_API_AUDIENCE
                        + "\\\"|\\[[^]]*\\\"" + ACCESS_REQUESTS_API_AUDIENCE + "\\\"[^]]*])")
                .matcher(payload)
                .find();
    }

    private void insertPublishedEntitlement(String entitlementId) throws SQLException {
        insertPublishedEntitlement(entitlementId, "access-request-approver");
    }

    private void insertPublishedEntitlement(String entitlementId, String approverRoleId) throws SQLException {
        insertEntitlement(
                entitlementId,
                "CLIENT_ROLE",
                "finance-reader-" + entitlementId,
                approverRoleId,
                true);
    }

    private void insertEntitlement(
            String entitlementId, String resourceType, String resourceId, boolean requestable) throws SQLException {
        insertEntitlement(
                entitlementId,
                resourceType,
                resourceId,
                "access-request-approver",
                requestable);
    }

    private void insertEntitlement(
            String entitlementId,
            String resourceType,
            String resourceId,
            String approverRoleId,
            boolean requestable) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     insert into AR_ENTITLEMENT (
                         ID,
                         REALM_ID,
                         RESOURCE_TYPE,
                         RESOURCE_ID,
                         DISPLAY_NAME,
                         DESCRIPTION,
                         RISK_LEVEL,
                         APPROVER_ROLE_ID,
                         REQUESTABLE,
                         CREATED_TIMESTAMP,
                         UPDATED_TIMESTAMP,
                         VERSION)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            long now = Instant.now().toEpochMilli();
            statement.setString(1, entitlementId);
            statement.setString(2, masterRealmId(connection));
            statement.setString(3, resourceType);
            statement.setString(4, resourceId);
            statement.setString(5, "Finance Reader");
            statement.setString(6, "Read-only access to the Finance Portal.");
            statement.setString(7, "LOW");
            statement.setString(8, approverRoleId);
            statement.setBoolean(9, requestable);
            statement.setLong(10, now);
            statement.setLong(11, now);
            statement.setLong(12, 0);
            statement.executeUpdate();
        }
    }

    private void insertPendingRequestFromAnotherRealm(String requestId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     insert into AR_ACCESS_REQUEST (
                         ID,
                         REALM_ID,
                         REQUESTER_ID,
                         ENTITLEMENT_ID,
                         RESOURCE_TYPE,
                         RESOURCE_ID,
                         RESOURCE_NAME_SNAPSHOT,
                         JUSTIFICATION,
                         DECISION_STATUS,
                         PROVISIONING_STATUS,
                         CREATED_TIMESTAMP,
                         UPDATED_TIMESTAMP,
                         VERSION)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            long now = Instant.now().toEpochMilli();
            statement.setString(1, requestId);
            statement.setString(2, "another-realm-" + UUID.randomUUID());
            statement.setString(3, "another-requester");
            statement.setString(4, UUID.randomUUID().toString());
            statement.setString(5, "REALM_ROLE");
            statement.setString(6, "another-resource");
            statement.setString(7, "Another Resource");
            statement.setString(8, "A request that belongs to another realm.");
            statement.setString(9, "PENDING");
            statement.setString(10, "NOT_STARTED");
            statement.setLong(11, now);
            statement.setLong(12, now);
            statement.setLong(13, 0);
            statement.executeUpdate();
        }
    }

    private String masterRealmId(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select ID from REALM where NAME = 'master'")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "The master realm must exist.");
                return result.getString(1);
            }
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("select to_regclass(?)")) {
            statement.setString(1, "public." + tableName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1) != null;
            }
        }
    }

    private long providerChangeSetCount(Connection connection) throws SQLException {
        String changelogTable = providerChangelogTable(connection);
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from " + changelogTable)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String providerChangelogTable(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                  and table_name like 'databasechangelog_access_req%'
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "Keycloak must create the provider-specific changelog table.");
                return result.getString(1);
            }
        }
    }
}
