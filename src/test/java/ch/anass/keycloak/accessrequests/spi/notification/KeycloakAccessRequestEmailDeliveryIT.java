package ch.anass.keycloak.accessrequests.spi.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class KeycloakAccessRequestEmailDeliveryIT {

    private static final String ACCESS_REQUESTS_API_AUDIENCE = "access-requests-api";
    private static final String DEFAULT_KEYCLOAK_VERSION = "26.7.3";
    private static final String DEFAULT_MAILPIT_CONTAINER = "axllent/mailpit:v1.30.7";
    private static final String FROM_ADDRESS = "no-reply@access-requests.test";
    private static final String FROM_DISPLAY_NAME = "Access requests";
    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version", DEFAULT_KEYCLOAK_VERSION);
    private static final String KEYCLOAK_IMAGE = System.getProperty(
            "keycloak.image", "quay.io/keycloak/keycloak:" + KEYCLOAK_VERSION);
    private static final String MAILPIT_CONTAINER = System.getProperty("mailpit.container", DEFAULT_MAILPIT_CONTAINER);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);
    private static final int KEYCLOAK_LOG_TAIL_LENGTH = 8_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Network NETWORK = Network.newNetwork();

    private final StringBuilder keycloakLogs = new StringBuilder();

    @Container
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse(MAILPIT_CONTAINER))
            .withNetwork(NETWORK)
            .withNetworkAliases("mailpit")
            .withExposedPorts(8025);

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @Test
    void deliversLifecycleEmailsUsingTheDeployedProviderAndEmailTheme() throws Exception {
        try (KeycloakContainer keycloak = keycloak()) {
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);

            String adminToken = accessToken(keycloak, "admin-cli", "admin", "admin");
            configureEmail(keycloak, adminToken);
            String clientId = "email-notification-client-" + UUID.randomUUID();
            createDirectAccessClient(keycloak, adminToken, clientId);
            addAccessRequestsAudience(keycloak, adminToken, clientId);

            TestUser approver = createUser(
                    keycloak, adminToken, clientId, "approver", "approver@example.test", "en");
            String approverRoleName = "email-approver-" + UUID.randomUUID();
            String approverRoleId = createRealmRoleAndAssignToUser(
                    keycloak,
                    adminToken,
                    approver.id(),
                    approverRoleName);
            TestUser frenchApprover = createUser(
                    keycloak, adminToken, clientId, "french-approver", "french-approver@example.test", "fr");
            assignRealmRoleToUser(
                    keycloak,
                    adminToken,
                    frenchApprover.id(),
                    approverRoleId,
                    approverRoleName);

            TestUser approvedRequester = createUser(
                    keycloak, adminToken, clientId, "approved-requester", "approved-requester@example.test", "en");
            String approvedEntitlementId = createRequestableEntitlement(
                    keycloak,
                    adminToken,
                    approverRoleId,
                    "Approved Finance Reports");
            String approvedRequestId = submit(
                    keycloak,
                    approvedRequester.token(),
                    approvedEntitlementId,
                    "I need read-only finance reports.");
            assertDeliveredMessage(
                    approver.email(),
                    "New access request",
                    "I need read-only finance reports.",
                    "Approved Finance Reports");
            assertDeliveredMessage(
                    frenchApprover.email(),
                    "Nouvelle demande d’accès",
                    "Une nouvelle demande d’accès a été soumise.",
                    "I need read-only finance reports.",
                    "Approved Finance Reports");
            approve(keycloak, approver.token(), approvedRequestId);
            assertDeliveredMessage(
                    approvedRequester.email(),
                    "Access request approved",
                    "Your access request was approved.",
                    "Approved Finance Reports");

            TestUser rejectedRequester = createUser(
                    keycloak, adminToken, clientId, "rejected-requester", "rejected-requester@example.test", "fr");
            String rejectedEntitlementId = createRequestableEntitlement(
                    keycloak,
                    adminToken,
                    approverRoleId,
                    "Rejected Finance Reports");
            String rejectedRequestId = submit(
                    keycloak,
                    rejectedRequester.token(),
                    rejectedEntitlementId,
                    "I only need this access temporarily.");
            assertDeliveredMessage(
                    approver.email(),
                    "New access request",
                    "I only need this access temporarily.",
                    "Rejected Finance Reports");
            reject(keycloak, approver.token(), rejectedRequestId);
            assertDeliveredMessage(
                    rejectedRequester.email(),
                    "Demande d’accès refusée",
                    "Votre demande d’accès a été refusée.",
                    "Rejected Finance Reports");

            TestUser failedRequester = createUser(
                    keycloak, adminToken, clientId, "failed-requester", "failed-requester@example.test", "zh-CN");
            EntitlementTarget retiredEntitlement = createRequestableEntitlementTarget(
                    keycloak,
                    adminToken,
                    approverRoleId,
                    "Retired Finance Reports");
            String failedRequestId = submit(
                    keycloak,
                    failedRequester.token(),
                    retiredEntitlement.entitlementId(),
                    "I need reports from the retired system.");
            assertDeliveredMessage(
                    approver.email(),
                    "New access request",
                    "I need reports from the retired system.",
                    "Retired Finance Reports");
            deleteRealmRole(keycloak, adminToken, retiredEntitlement.roleName());
            approve(keycloak, approver.token(), failedRequestId);
            assertDeliveredMessage(
                    failedRequester.email(),
                    "Access request approved",
                    "Your access request was approved.",
                    "Retired Finance Reports");
            assertDeliveredMessage(
                    failedRequester.email(),
                    "Access request provisioning failed",
                    "Your approved access request could not be provisioned.",
                    "Retired Finance Reports");
        }
    }

    private KeycloakContainer keycloak() {
        return new KeycloakContainer(KEYCLOAK_IMAGE)
                .withNetwork(NETWORK)
                .withAdminUsername("admin")
                .withAdminPassword("admin")
                .withProviderLibsFrom(List.of(providerJar().toFile()))
                .withLogConsumer(output -> {
                    synchronized (keycloakLogs) {
                        keycloakLogs.append(output.getUtf8String());
                        int surplus = keycloakLogs.length() - KEYCLOAK_LOG_TAIL_LENGTH;
                        if (surplus > 0) {
                            keycloakLogs.delete(0, surplus);
                        }
                    }
                })
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private Path providerJar() {
        Path providerJar = Path.of("target", "keycloak-access-requests.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(providerJar), "The provider JAR must be built before integration tests run.");
        return providerJar;
    }

    private void configureAdminCliTokenBehavior(KeycloakContainer keycloak) throws Exception {
        try {
            Method method = KeycloakContainer.class.getMethod(
                    "disableLightweightAccessTokenForAdminCliClient", String.class);
            method.invoke(keycloak, "master");
        } catch (NoSuchMethodException ignored) {
            // This helper is only needed by the Keycloak 26.7 test container.
        }
    }

    private void configureEmail(KeycloakContainer keycloak, String adminToken) throws Exception {
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master", adminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "emailTheme":"access-requests",
                                  "internationalizationEnabled":true,
                                  "supportedLocales":["en","fr"],
                                  "defaultLocale":"en",
                                  "smtpServer":{
                                    "host":"mailpit",
                                    "port":"1025",
                                    "from":"no-reply@access-requests.test",
                                    "fromDisplayName":"Access requests",
                                    "auth":"false",
                                    "ssl":"false",
                                    "starttls":"false"
                                  }
                                }
                                """))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private TestUser createUser(
            KeycloakContainer keycloak,
            String adminToken,
            String clientId,
            String usernamePrefix,
            String email,
            String locale) throws Exception {
        String username = usernamePrefix + "-" + UUID.randomUUID();
        String password = "password";
        String attributes = locale == null ? "" : "\"attributes\":{\"locale\":[\"%s\"]},".formatted(locale);
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users", adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "username":"%s",
                                  %s
                                  "email":"%s",
                                  "emailVerified":true,
                                  "enabled":true,
                                  "credentials":[{"type":"password","value":"%s","temporary":false}]
                                }
                                """.formatted(username, attributes, email, password)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode());

        String token = accessToken(keycloak, clientId, username, password);
        return new TestUser(email, token, subjectOf(token));
    }

    private void createDirectAccessClient(KeycloakContainer keycloak, String adminToken, String clientId)
            throws Exception {
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/clients", adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"clientId":"%s","enabled":true,"publicClient":true,"directAccessGrantsEnabled":true}
                                """.formatted(clientId)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode());
    }

    private void addAccessRequestsAudience(
            KeycloakContainer keycloak,
            String adminToken,
            String clientId) throws Exception {
        ensureAccessRequestsApiClient(keycloak, adminToken);
        String scopeId = ensureAccessRequestsApiClientScope(keycloak, adminToken);
        String clientInternalId = clientInternalId(keycloak, adminToken, clientId);
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(
                                keycloak,
                                "/admin/realms/master/clients/%s/default-client-scopes/%s"
                                        .formatted(clientInternalId, scopeId),
                                adminToken)
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private void ensureAccessRequestsApiClient(KeycloakContainer keycloak, String adminToken) throws Exception {
        if (clientInternalIdOrNull(keycloak, adminToken, ACCESS_REQUESTS_API_AUDIENCE) != null) {
            return;
        }
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/clients", adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "clientId":"access-requests-api",
                                  "enabled":true,
                                  "protocol":"openid-connect",
                                  "standardFlowEnabled":false,
                                  "directAccessGrantsEnabled":false,
                                  "serviceAccountsEnabled":false
                                }
                                """))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode());
    }

    private String ensureAccessRequestsApiClientScope(KeycloakContainer keycloak, String adminToken) throws Exception {
        String existingScope = clientScopeIdOrNull(keycloak, adminToken);
        if (existingScope != null) {
            return existingScope;
        }
        HttpResponse<Void> scopeResponse = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/client-scopes", adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"access-requests-api","protocol":"openid-connect"}
                                """))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, scopeResponse.statusCode());

        String scopeId = clientScopeId(keycloak, adminToken);
        HttpResponse<Void> mapperResponse = HTTP_CLIENT.send(
                adminRequest(
                                keycloak,
                                "/admin/realms/master/client-scopes/%s/protocol-mappers/models".formatted(scopeId),
                                adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "name":"access-requests-api-%s",
                                  "protocol":"openid-connect",
                                  "protocolMapper":"oidc-audience-mapper",
                                  "config":{
                                    "included.client.audience":"access-requests-api",
                                    "access.token.claim":"true",
                                    "id.token.claim":"false",
                                    "introspection.token.claim":"true"
                                  }
                                }
                                """.formatted(UUID.randomUUID())))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, mapperResponse.statusCode());
        return scopeId;
    }

    private String createRealmRoleAndAssignToUser(
            KeycloakContainer keycloak,
            String adminToken,
            String userId,
            String roleName) throws Exception {
        String roleId = createRealmRole(keycloak, adminToken, roleName);
        assignRealmRoleToUser(keycloak, adminToken, userId, roleId, roleName);
        return roleId;
    }

    private void assignRealmRoleToUser(
            KeycloakContainer keycloak,
            String adminToken,
            String userId,
            String roleId,
            String roleName) throws Exception {
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(
                                keycloak,
                                "/admin/realms/master/users/%s/role-mappings/realm".formatted(userId),
                                adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                [{"id":"%s","name":"%s"}]
                                """.formatted(roleId, roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private String createRealmRole(KeycloakContainer keycloak, String adminToken, String roleName) throws Exception {
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/roles", adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"%s"}
                                """.formatted(roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode());

        HttpResponse<String> role = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/roles/%s".formatted(roleName), adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, role.statusCode());
        return responseId(role.body());
    }

    private String createRequestableEntitlement(
            KeycloakContainer keycloak,
            String adminToken,
            String approverRoleId,
            String displayName) throws Exception {
        return createRequestableEntitlementTarget(keycloak, adminToken, approverRoleId, displayName).entitlementId();
    }

    private EntitlementTarget createRequestableEntitlementTarget(
            KeycloakContainer keycloak,
            String adminToken,
            String approverRoleId,
            String displayName) throws Exception {
        String roleName = "email-target-" + UUID.randomUUID();
        String roleId = createRealmRole(keycloak, adminToken, roleName);
        URI entitlementsEndpoint = realmEndpoint(keycloak, "/realms/master/access-requests/admin/entitlements");
        String description = "Read-only access for email delivery verification.";
        HttpResponse<String> creation = HTTP_CLIENT.send(
                HttpRequest.newBuilder(entitlementsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "resourceType":"REALM_ROLE",
                                  "resourceId":"%s",
                                  "displayName":"%s",
                                  "description":"%s",
                                  "riskLevel":"HIGH",
                                  "approverRoleId":"%s"
                                }
                                """.formatted(roleId, displayName, description, approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, creation.statusCode());
        String entitlementId = responseId(creation.body());

        HttpResponse<String> update = HTTP_CLIENT.send(
                HttpRequest.newBuilder(URI.create(entitlementsEndpoint + "/" + entitlementId))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "displayName":"%s",
                                  "description":"%s",
                                  "riskLevel":"HIGH",
                                  "approverRoleId":"%s",
                                  "requestable":true,
                                  "version":0
                                }
                                """.formatted(displayName, description, approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, update.statusCode());
        return new EntitlementTarget(entitlementId, roleName);
    }

    private String submit(KeycloakContainer keycloak, String token, String entitlementId, String justification)
            throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                accessRequestRequest(keycloak, "/requests", token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"entitlementId":"%s","justification":"%s"}
                                """.formatted(entitlementId, justification)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        return responseId(response.body());
    }

    private void approve(KeycloakContainer keycloak, String token, String requestId) throws Exception {
        decide(keycloak, token, requestId, "approve", "Approved for delivery verification.");
    }

    private void reject(KeycloakContainer keycloak, String token, String requestId) throws Exception {
        decide(keycloak, token, requestId, "reject", "Rejected for delivery verification.");
    }

    private void decide(KeycloakContainer keycloak, String token, String requestId, String decision, String comment)
            throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                accessRequestRequest(keycloak, "/%s/%s".formatted(requestId, decision), token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"comment":"%s"}
                                """.formatted(comment)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    private void deleteRealmRole(KeycloakContainer keycloak, String adminToken, String roleName) throws Exception {
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/roles/%s".formatted(roleName), adminToken)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private void assertDeliveredMessage(String recipient, String subject, String... expectedContent) throws Exception {
        JsonNode message = waitForMessage(recipient, subject);
        assertEquals(subject, message.path("Subject").asText());
        assertEquals(FROM_ADDRESS, message.path("From").path("Address").asText());
        assertEquals(FROM_DISPLAY_NAME, message.path("From").path("Name").asText());
        assertTrue(message.path("To").toString().contains(recipient), "The email must be delivered to its recipient.");

        String text = message.path("Text").asText();
        String html = message.path("HTML").asText();
        assertTrue(!text.isBlank(), "The delivered email must include a plain-text MIME part.");
        assertTrue(!html.isBlank(), "The delivered email must include an HTML MIME part.");
        for (String expected : expectedContent) {
            assertTrue(
                    text.contains(expected),
                    () -> "Expected plain-text email to contain '" + expected + "': " + text);
            assertTrue(
                    html.contains(expected),
                    () -> "Expected HTML email to contain '" + expected + "': " + html);
        }
    }

    private JsonNode waitForMessage(String recipient, String subject) throws Exception {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);
        String latestMessages = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    HttpRequest.newBuilder(mailpitEndpoint("/api/v1/messages?limit=100")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            latestMessages = response.body();
            JsonNode messages = JSON.readTree(latestMessages).path("messages");
            for (JsonNode summary : messages) {
                if (summary.toString().contains(recipient) && summary.path("Subject").asText().equals(subject)) {
                    String id = summary.path("ID").asText();
                    assertTrue(!id.isBlank(), "Mailpit must return the identifier of each delivered email.");
                    HttpResponse<String> detail = HTTP_CLIENT.send(
                            HttpRequest.newBuilder(mailpitEndpoint("/api/v1/message/" + id)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, detail.statusCode());
                    return JSON.readTree(detail.body());
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No email for %s with subject '%s' was delivered within %s. Mailpit messages: %s"
                .formatted(recipient, subject, DELIVERY_TIMEOUT, latestMessages)
                + "\nKeycloak log tail:\n" + keycloakLogTail());
    }

    private String accessToken(KeycloakContainer keycloak, String clientId, String username, String password)
            throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder(realmEndpoint(keycloak, "/realms/master/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=password&client_id=%s&username=%s&password=%s"
                                        .formatted(clientId, username, password)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return tokenValue(response.body());
    }

    private String clientInternalId(KeycloakContainer keycloak, String adminToken, String clientId) throws Exception {
        String id = clientInternalIdOrNull(keycloak, adminToken, clientId);
        assertTrue(id != null, "The configured client must have an internal identifier.");
        return id;
    }

    private String clientInternalIdOrNull(
            KeycloakContainer keycloak,
            String adminToken,
            String clientId) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/clients?clientId=" + clientId, adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode clients = JSON.readTree(response.body());
        for (JsonNode client : clients) {
            if (clientId.equals(client.path("clientId").asText())) {
                return client.path("id").asText();
            }
        }
        return null;
    }

    private String clientScopeId(KeycloakContainer keycloak, String adminToken) throws Exception {
        String id = clientScopeIdOrNull(keycloak, adminToken);
        assertTrue(id != null, "The access requests client scope must exist.");
        return id;
    }

    private String clientScopeIdOrNull(KeycloakContainer keycloak, String adminToken) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(
                                keycloak,
                                "/admin/realms/master/client-scopes?search=" + ACCESS_REQUESTS_API_AUDIENCE,
                                adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode clientScopes = JSON.readTree(response.body());
        for (JsonNode clientScope : clientScopes) {
            if (ACCESS_REQUESTS_API_AUDIENCE.equals(clientScope.path("name").asText())) {
                return clientScope.path("id").asText();
            }
        }
        return null;
    }

    private String responseId(String response) {
        JsonNode body;
        try {
            body = JSON.readTree(response);
        } catch (Exception exception) {
            throw new AssertionError("Expected a JSON response with an identifier: " + response, exception);
        }
        String id = body.path("id").asText();
        assertTrue(!id.isBlank(), "The response must contain an identifier.");
        return id;
    }

    private String tokenValue(String response) {
        var matcher = Pattern.compile("\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
        assertTrue(matcher.find(), "The token response must contain an access token.");
        return matcher.group(1);
    }

    private String subjectOf(String token) {
        String[] segments = token.split("\\.");
        assertEquals(3, segments.length, "The access token must be a JWT.");
        String payload = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\\\"sub\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(payload);
        assertTrue(matcher.find(), "The access token payload must contain a subject.");
        return matcher.group(1);
    }

    private HttpRequest.Builder adminRequest(KeycloakContainer keycloak, String path, String adminToken) {
        return HttpRequest.newBuilder(realmEndpoint(keycloak, path))
                .header("Authorization", "Bearer " + adminToken);
    }

    private HttpRequest.Builder accessRequestRequest(KeycloakContainer keycloak, String path, String token) {
        return HttpRequest.newBuilder(realmEndpoint(keycloak, "/realms/master/access-requests" + path))
                .header("Authorization", "Bearer " + token);
    }

    private URI realmEndpoint(KeycloakContainer keycloak, String path) {
        return URI.create("http://%s:%d%s".formatted(keycloak.getHost(), keycloak.getMappedPort(8080), path));
    }

    private URI mailpitEndpoint(String path) {
        return URI.create("http://%s:%d%s".formatted(MAILPIT.getHost(), MAILPIT.getMappedPort(8025), path));
    }

    private String keycloakLogTail() {
        synchronized (keycloakLogs) {
            return keycloakLogs.toString();
        }
    }

    private record TestUser(String email, String token, String id) {
    }

    private record EntitlementTarget(String entitlementId, String roleName) {
    }
}
