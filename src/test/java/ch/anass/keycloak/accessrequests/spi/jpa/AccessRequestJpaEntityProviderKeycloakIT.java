package ch.anass.keycloak.accessrequests.spi.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class AccessRequestJpaEntityProviderKeycloakIT {

    private static final String ACCOUNT_CONSOLE_RESOURCE_PATH = "theme/access-requests/account/resources/";
    private static final String ADMIN_CONSOLE_RESOURCE_PATH = "theme/access-requests/admin/resources/";
    private static final String ACCESS_REQUESTS_API_AUDIENCE = "access-requests-api";
    private static final String ACCESS_REQUEST_MANAGER_ROLE = "manage-access-requests";
    private static final String DEFAULT_KEYCLOAK_VERSION = "26.7.4";
    private static final String DEFAULT_POSTGRESQL_CONTAINER = "mirror.gcr.io/postgres:18";
    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version", DEFAULT_KEYCLOAK_VERSION);
    private static final String KEYCLOAK_IMAGE = System.getProperty(
            "keycloak.image", "quay.io/keycloak/keycloak:" + KEYCLOAK_VERSION);
    private static final String POSTGRESQL_CONTAINER = System.getProperty(
            "postgresql.container", DEFAULT_POSTGRESQL_CONTAINER);
    private static final DockerImageName POSTGRESQL_IMAGE = DockerImageName.parse(POSTGRESQL_CONTAINER)
            .asCompatibleSubstituteFor("postgres");
    private static final Network NETWORK = Network.newNetwork();
    private String accessPackageEntitlementId;
    private String accessPackageGroupId;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRESQL_IMAGE)
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
        assertConsoleBundlesArePackagedInProviderJar(providerJar());

        try (KeycloakContainer firstServer = keycloak()) {
            firstServer.start();
            configureAdminCliTokenBehavior(firstServer);
            assertAccountThemeMessagesAreServedFromTheDeployedProviderJar(firstServer);
            assertProviderSchemaApplied();
            assertRealmEndpointExposed(firstServer);
            assertEntitlementCatalogAdministration(firstServer);
            assertAccessPackageCreationAndBinding(firstServer);
            assertCatalogEndpointRequiresAuthenticationAndListsPublishedEntitlements(firstServer);
            assertRequestSubmissionRequiresAudienceAndCreatesAnAuditedPendingRequest(firstServer);
            assertRequesterCanListViewAndCancelOnlyOwnRequests(firstServer);
            assertEntitlementScopedApproversCanDecideRequests(firstServer);
            assertAdministrativeAuditEventSearch(firstServer);
        }

        try (KeycloakContainer restartedServer = keycloak()) {
            restartedServer.start();
            configureAdminCliTokenBehavior(restartedServer);
            assertAccountThemeMessagesAreServedFromTheDeployedProviderJar(restartedServer);
            assertProviderSchemaApplied();
            assertRealmEndpointExposed(restartedServer);
            assertAccessPackageSurvivesRestart(restartedServer);
            assertEntitlementCatalogAdministration(restartedServer);
            assertCatalogEndpointRequiresAuthenticationAndListsPublishedEntitlements(restartedServer);
            assertRequestSubmissionRequiresAudienceAndCreatesAnAuditedPendingRequest(restartedServer);
            assertRequesterCanListViewAndCancelOnlyOwnRequests(restartedServer);
            assertEntitlementScopedApproversCanDecideRequests(restartedServer);
            assertAdministrativeAuditEventSearch(restartedServer);
        }
    }

    private KeycloakContainer keycloak() {
        Path providerJar = providerJar();

        return new KeycloakContainer(KEYCLOAK_IMAGE)
                .withNetwork(NETWORK)
                .withEnv("KC_DB", "postgres")
                .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/keycloak")
                .withEnv("KC_DB_USERNAME", "keycloak")
                .withEnv("KC_DB_PASSWORD", "keycloak")
                .withAdminUsername("admin")
                .withAdminPassword("admin")
                .withProviderLibsFrom(List.of(providerJar.toFile()))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private Path providerJar() {
        Path providerJar = Path.of("target", "keycloak-access-requests.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(providerJar), "The provider JAR must be built before integration tests run.");
        return providerJar;
    }

    private void assertConsoleBundlesArePackagedInProviderJar(Path providerJar) throws Exception {
        try (JarFile provider = new JarFile(providerJar.toFile())) {
            JsonNode manifest = new ObjectMapper().readTree(
                    readJarEntry(provider, ACCOUNT_CONSOLE_RESOURCE_PATH + ".vite/manifest.json"));
            assertTrue(manifest.isObject(), "The Vite manifest must be a JSON object.");

            JsonNode entryPoint = manifest.get("src/account/main.tsx");
            assertTrue(entryPoint != null && entryPoint.isObject(),
                    "The Vite manifest must declare the Account Console entrypoint.");
            assertManifestReferences(provider, manifest, ACCOUNT_CONSOLE_RESOURCE_PATH, "src/account/main.tsx", new HashSet<>());

            assertTrue(manifestReferences(entryPoint, "dynamicImports").containsAll(Set.of(
                    "src/account/pages/RequestAccessRoutePage.tsx",
                    "src/account/pages/MyRequestsRoutePage.tsx",
                    "src/account/pages/ApprovalsRoutePage.tsx")),
                    "The Account Console entrypoint must lazy-load all access request routes.");

            JsonNode adminManifest = new ObjectMapper().readTree(
                    readJarEntry(provider, ADMIN_CONSOLE_RESOURCE_PATH + ".vite/manifest.json"));
            assertTrue(adminManifest.isObject(), "The Admin Console Vite manifest must be a JSON object.");

            JsonNode adminEntryPoint = adminManifest.get("src/admin/main.tsx");
            assertTrue(adminEntryPoint != null && adminEntryPoint.isObject(),
                    "The Vite manifest must declare the Admin Console entrypoint.");
            assertManifestReferences(provider, adminManifest, ADMIN_CONSOLE_RESOURCE_PATH, "src/admin/main.tsx", new HashSet<>());
            assertTrue(manifestReferences(adminEntryPoint, "dynamicImports").containsAll(Set.of(
                    "src/admin/pages/EntitlementCatalogRoute.tsx",
                    "src/admin/pages/NotificationDeliveryRoute.tsx")),
                    "The Admin Console entrypoint must lazy-load catalog and notification delivery routes.");
        }
    }

    private void assertManifestReferences(
            JarFile provider,
            JsonNode manifest,
            String resourcePath,
            String manifestEntryName,
            Set<String> visitedEntries) {
        if (!visitedEntries.add(manifestEntryName)) {
            return;
        }

        JsonNode manifestEntry = manifest.get(manifestEntryName);
        assertTrue(manifestEntry != null && manifestEntry.isObject(),
                () -> "The Vite manifest entry '" + manifestEntryName + "' must exist.");

        assertManifestFileIsPackaged(provider, resourcePath, manifestEntryName, "file", manifestEntry.get("file"));
        assertManifestFilesArePackaged(provider, resourcePath, manifestEntryName, "css", manifestEntry.get("css"));
        assertManifestFilesArePackaged(provider, resourcePath, manifestEntryName, "assets", manifestEntry.get("assets"));
        assertManifestDependenciesArePackaged(provider, manifest, resourcePath, manifestEntryName, "imports", manifestEntry.get("imports"),
                visitedEntries);
        assertManifestDependenciesArePackaged(provider, manifest, resourcePath, manifestEntryName, "dynamicImports",
                manifestEntry.get("dynamicImports"), visitedEntries);
    }

    private void assertManifestDependenciesArePackaged(
            JarFile provider,
            JsonNode manifest,
            String resourcePath,
            String manifestEntryName,
            String field,
            JsonNode dependencies,
            Set<String> visitedEntries) {
        if (dependencies == null) {
            return;
        }

        assertTrue(dependencies.isArray(),
                () -> "The Vite manifest field '" + field + "' for '" + manifestEntryName + "' must be an array.");
        for (JsonNode dependency : dependencies) {
            assertTrue(dependency.isTextual(),
                    () -> "The Vite manifest field '" + field + "' for '" + manifestEntryName
                            + "' must only contain manifest entry names.");
            assertManifestReferences(provider, manifest, resourcePath, dependency.asText(), visitedEntries);
        }
    }

    private void assertManifestFilesArePackaged(
            JarFile provider, String resourcePath, String manifestEntryName, String field, JsonNode files) {
        if (files == null) {
            return;
        }

        assertTrue(files.isArray(),
                () -> "The Vite manifest field '" + field + "' for '" + manifestEntryName + "' must be an array.");
        for (JsonNode file : files) {
            assertManifestFileIsPackaged(provider, resourcePath, manifestEntryName, field, file);
        }
    }

    private void assertManifestFileIsPackaged(
            JarFile provider, String resourcePath, String manifestEntryName, String field, JsonNode file) {
        assertTrue(file != null && file.isTextual(),
                () -> "The Vite manifest field '" + field + "' for '" + manifestEntryName + "' must be a file path.");

        String filePath = file.asText();
        assertTrue(isSafeRelativeResourcePath(filePath),
                () -> "The Vite manifest must use a safe relative resource path, but found '" + filePath + "'.");
        assertTrue(provider.getJarEntry(resourcePath + filePath) != null,
                () -> "The provider JAR must package Vite " + field + " '" + filePath
                        + "' referenced by '" + manifestEntryName + "'.");
    }

    private Set<String> manifestReferences(JsonNode manifestEntry, String field) {
        JsonNode references = manifestEntry.get(field);
        if (references == null) {
            return Set.of();
        }

        assertTrue(references.isArray(), () -> "The Vite manifest field '" + field + "' must be an array.");
        Set<String> names = new HashSet<>();
        for (JsonNode reference : references) {
            assertTrue(reference.isTextual(),
                    () -> "The Vite manifest field '" + field + "' must only contain manifest entry names.");
            names.add(reference.asText());
        }
        return names;
    }

    private boolean isSafeRelativeResourcePath(String path) {
        return !path.isBlank()
                && !path.startsWith("/")
                && !path.contains("\\")
                && !path.contains("../")
                && !path.equals("..");
    }

    private String readJarEntry(JarFile provider, String path) throws Exception {
        var entry = provider.getJarEntry(path);
        assertTrue(entry != null, () -> "The provider JAR must package " + path + ".");
        try (var input = provider.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void configureAdminCliTokenBehavior(KeycloakContainer server) throws Exception {
        try {
            Method method = KeycloakContainer.class.getMethod(
                    "disableLightweightAccessTokenForAdminCliClient", String.class);
            method.invoke(server, "master");
        } catch (NoSuchMethodException ignored) {
            // This helper is only needed by the Keycloak 26.7 test container.
        }
    }

    private void assertProviderSchemaApplied() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertTrue(tableExists(connection, "ar_access_request"));
            assertTrue(tableExists(connection, "ar_access_grant"));
            assertTrue(tableExists(connection, "ar_access_package"));
            assertTrue(tableExists(connection, "ar_access_package_role"));
            assertTrue(tableExists(connection, "ar_access_request_history"));
            try (ResultSet columns = connection.getMetaData().getColumns(
                    null, "public", "ar_access_request_history", "request_version")) {
                assertTrue(columns.next(), "Failure event versions must be available after migration.");
            }
            assertTrue(tableExists(connection, "ar_entitlement"));
            assertTrue(tableExists(connection, "ar_entitlement_history"));
            assertTrue(tableExists(connection, "ar_notification_outbox"));
            assertEquals(2, providerChangeSetCount(connection));
        }
    }

    private void assertAccessPackageCreationAndBinding(GenericContainer<?> server) throws Exception {
        String adminToken = accessToken(server, "admin-cli");
        URI realmEndpoint = URI.create("http://%s:%d/admin/realms/master"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<String> realmResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(realmEndpoint).header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, realmResponse.statusCode());
        String realmId = new ObjectMapper().readTree(realmResponse.body()).path("id").asText();
        String sourceRoleName = "jit-source-" + UUID.randomUUID();
        String sourceRoleId = createRealmRole(server, adminToken, sourceRoleName);
        String approverRoleName = "package-approver-" + UUID.randomUUID();
        String approverRoleId = createRealmRole(server, adminToken, approverRoleName);
        URI packagesEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/access-packages"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        String submission = """
                {
                  "displayName":"Temporary reporting access",
                  "description":"Time-bound reporting package.",
                  "riskLevel":"MEDIUM",
                  "approverRoleId":"%s",
                  "defaultDurationSeconds":604800,
                  "maxDurationSeconds":2592000,
                  "allowPermanent":false,
                  "roleMappings":[{"type":"REALM_ROLE","roleId":"%s"}]
                }
                """.formatted(approverRoleId, sourceRoleId);
        HttpClient client = HttpClient.newHttpClient();

        assertEquals(401, client.send(HttpRequest.newBuilder(packagesEndpoint)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(submission)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

        String clientId = "access-package-admin-test-" + UUID.randomUUID();
        createDirectAccessClient(server, adminToken, clientId);
        String username = "access-package-viewer-" + UUID.randomUUID();
        String password = "access-package-viewer-password";
        createEnabledUser(server, adminToken, username, password);
        String viewerToken = accessToken(server, clientId, username, password);
        assignRealmManagementRoles(server, adminToken, subjectOf(viewerToken), "view-realm");
        viewerToken = accessToken(server, clientId, username, password);
        assertEquals(403, client.send(HttpRequest.newBuilder(packagesEndpoint)
                        .header("Authorization", "Bearer " + viewerToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(submission)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

        int groupsBeforeInvalidRequest = accessPackageGroupCount(server, adminToken);
        for (String invalidSubmission : List.of(
                submission.replace("\"type\":\"REALM_ROLE\"", "\"type\":\"CLIENT_ROLE\""),
                submission.replace("\"type\":\"REALM_ROLE\"", "\"type\":\"GROUP\""),
                submission.replace(sourceRoleId, "missing-role-" + UUID.randomUUID()),
                submission.replace("\"roleMappings\":[{\"type\":\"REALM_ROLE\",\"roleId\":\""
                        + sourceRoleId + "\"}]", "\"roleMappings\":[]"))) {
            HttpResponse<Void> invalidRole = client.send(HttpRequest.newBuilder(packagesEndpoint)
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(invalidSubmission))
                            .build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(400, invalidRole.statusCode());
            assertEquals(groupsBeforeInvalidRequest, accessPackageGroupCount(server, adminToken),
                    "Invalid role selections must not leave an orphan Keycloak group");
        }

        assertBindingFailureRollsBackKeycloakGroup(server, adminToken, packagesEndpoint, submission);

        String managerUsername = "access-package-manager-" + UUID.randomUUID();
        String managerPassword = "access-package-manager-password";
        createEnabledUser(server, adminToken, managerUsername, managerPassword);
        String initialManagerToken = accessToken(server, clientId, managerUsername, managerPassword);
        assignRealmManagementRoles(server, adminToken, subjectOf(initialManagerToken), "view-realm");
        ensureRealmRoleAndAssignToUser(server, adminToken, subjectOf(initialManagerToken), ACCESS_REQUEST_MANAGER_ROLE);
        String managerToken = accessToken(server, clientId, managerUsername, managerPassword);

        HttpResponse<String> created = client.send(HttpRequest.newBuilder(packagesEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(submission)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        JsonNode entitlement = new ObjectMapper().readTree(created.body());
        String entitlementId = entitlement.path("id").asText();
        String groupId = entitlement.path("resourceId").asText();
        assertFalse(entitlementId.isBlank());
        assertFalse(groupId.isBlank());
        assertEquals("GROUP", entitlement.path("resourceType").asText());
        accessPackageEntitlementId = entitlementId;
        accessPackageGroupId = groupId;
        assertFalse(entitlement.path("requestable").asBoolean(),
                "A newly created access package must remain unpublished until the administrator enables it");
        assertEquals(groupsBeforeInvalidRequest + 1, accessPackageGroupCount(server, adminToken));

        URI entitlementEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/entitlements/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), entitlementId));
        HttpResponse<String> reloaded = client.send(HttpRequest.newBuilder(entitlementEndpoint)
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reloaded.statusCode());
        assertEquals(groupId, new ObjectMapper().readTree(reloaded.body()).path("resourceId").asText());

        URI packageDetailsEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/access-packages/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), entitlementId));
        HttpResponse<String> packageDetails = client.send(HttpRequest.newBuilder(packageDetailsEndpoint)
                        .header("Authorization", "Bearer " + managerToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, packageDetails.statusCode());
        JsonNode packageResponse = new ObjectMapper().readTree(packageDetails.body());
        assertEquals(groupId, packageResponse.path("groupId").asText());
        assertEquals("AR_PKG_" + entitlementId, packageResponse.path("groupName").asText());
        assertTrue(packageResponse.path("groupExists").asBoolean());
        assertTrue(packageResponse.path("configurationValid").asBoolean());
        assertEquals(sourceRoleId, packageResponse.path("roleMappings").get(0).path("roleId").asText());
        assertEquals(sourceRoleName, packageResponse.path("roleMappings").get(0).path("name").asText());
        assertEquals(403, client.send(HttpRequest.newBuilder(packageDetailsEndpoint)
                        .header("Authorization", "Bearer " + viewerToken).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

        URI groupEndpoint = URI.create("http://%s:%d/admin/realms/master/groups/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), groupId));
        HttpResponse<String> group = client.send(HttpRequest.newBuilder(groupEndpoint)
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, group.statusCode());
        assertEquals("AR_PKG_" + entitlementId, new ObjectMapper().readTree(group.body()).path("name").asText());

        URI groupRolesEndpoint = URI.create(groupEndpoint + "/role-mappings/realm");
        HttpResponse<String> groupRoles = client.send(HttpRequest.newBuilder(groupRolesEndpoint)
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, groupRoles.statusCode());
        assertTrue(java.util.stream.StreamSupport.stream(
                        new ObjectMapper().readTree(groupRoles.body()).spliterator(), false)
                .anyMatch(role -> sourceRoleId.equals(role.path("id").asText())));

        String roleRepresentations = groupRoles.body();
        assertEquals(204, client.send(HttpRequest.newBuilder(groupRolesEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(roleRepresentations)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        HttpResponse<String> misconfigured = client.send(HttpRequest.newBuilder(packageDetailsEndpoint)
                        .header("Authorization", "Bearer " + managerToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, misconfigured.statusCode());
        assertFalse(new ObjectMapper().readTree(misconfigured.body()).path("configurationValid").asBoolean());
        String publish = """
                {"displayName":"Temporary reporting access","description":"Time-bound reporting package.",
                 "riskLevel":"MEDIUM","approverRoleId":"%s","defaultDurationSeconds":604800,
                 "maxDurationSeconds":2592000,"allowPermanent":false,"requestable":true,"version":0}
                """.formatted(approverRoleId);
        HttpResponse<String> rejectedPublish = client.send(HttpRequest.newBuilder(entitlementEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(publish)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, rejectedPublish.statusCode(), rejectedPublish.body());
        assertError(rejectedPublish.body(), "INVALID_ACCESS_PACKAGE_CONFIGURATION", null);
        assertEquals(204, client.send(HttpRequest.newBuilder(groupRolesEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(roleRepresentations)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement binding = connection.prepareStatement(
                     "select realm_id, group_id, group_name from ar_access_package where entitlement_id = ?");
             PreparedStatement mapping = connection.prepareStatement(
                     "select role_type, role_id from ar_access_package_role where entitlement_id = ?")) {
            binding.setString(1, entitlementId);
            try (ResultSet row = binding.executeQuery()) {
                assertTrue(row.next(), "The group binding must be committed with the entitlement");
                assertEquals(realmId, row.getString("realm_id"));
                assertEquals(groupId, row.getString("group_id"));
                assertEquals("AR_PKG_" + entitlementId, row.getString("group_name"));
                assertFalse(row.next());
            }
            mapping.setString(1, entitlementId);
            try (ResultSet row = mapping.executeQuery()) {
                assertTrue(row.next(), "The selected role mapping must be persisted");
                assertEquals("REALM_ROLE", row.getString("role_type"));
                assertEquals(sourceRoleId, row.getString("role_id"));
                assertFalse(row.next());
            }
        }
        assertPackageGrantAuthorizationAfterProvisioning(server, adminToken, managerToken, entitlementEndpoint,
                entitlementId, groupId, approverRoleName, approverRoleId);
    }

    private void assertPackageGrantAuthorizationAfterProvisioning(
            GenericContainer<?> server, String adminToken, String managerToken, URI entitlementEndpoint,
            String entitlementId, String groupId, String approverRoleName, String approverRoleId) throws Exception {
        String publish = """
                {"displayName":"Temporary reporting access","description":"Time-bound reporting package.",
                 "riskLevel":"MEDIUM","approverRoleId":"%s","defaultDurationSeconds":604800,
                 "maxDurationSeconds":2592000,"allowPermanent":true,"requestable":true,"version":0}
                """.formatted(approverRoleId);
        HttpResponse<String> published = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(publish)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, published.statusCode(), published.body());

        String clientId = "package-grant-client-" + UUID.randomUUID();
        createDirectAccessClient(server, adminToken, clientId);
        addAccessRequestsAudience(server, adminToken, clientId);
        String approverUsername = "package-grant-approver-" + UUID.randomUUID();
        String password = "package-grant-password";
        createEnabledUser(server, adminToken, approverUsername, password);
        String approverToken = accessToken(server, clientId, approverUsername, password);
        assertEquals(approverRoleId, ensureRealmRoleAndAssignToUser(
                server, adminToken, subjectOf(approverToken), approverRoleName));
        approverToken = accessToken(server, clientId, approverUsername, password);

        URI accessRequestsEndpoint = URI.create("http://%s:%d/realms/master/access-requests"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        URI requestsEndpoint = URI.create(accessRequestsEndpoint + "/requests");
        String requesterUsername = "package-grant-requester-" + UUID.randomUUID();
        createEnabledUser(server, adminToken, requesterUsername, password);
        String requesterToken = accessToken(server, clientId, requesterUsername, password);
        String requesterId = subjectOf(requesterToken);
        String requestId = submitTemporaryPackageRequest(requestsEndpoint, requesterToken, entitlementId);
        HttpResponse<String> approval = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, requestId, "approve", "Approved."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approval.statusCode(), approval.body());
        assertTrue(approval.body().contains("\"provisioningStatus\":\"SUCCEEDED\""));
        assertGroupMembership(server, adminToken, requesterId, groupId);
        assertPackageGrantState(requestId, requesterId, groupId, "CREATED_BY_EXTENSION", "AUTHORIZED", 1);

        String preexistingUsername = "package-preexisting-requester-" + UUID.randomUUID();
        createEnabledUser(server, adminToken, preexistingUsername, password);
        String preexistingToken = accessToken(server, clientId, preexistingUsername, password);
        String preexistingId = subjectOf(preexistingToken);
        String preexistingRequestId = submitTemporaryPackageRequest(requestsEndpoint, preexistingToken, entitlementId);
        URI membershipEndpoint = URI.create("http://%s:%d/admin/realms/master/users/%s/groups/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), preexistingId, groupId));
        assertEquals(204, HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(membershipEndpoint).header("Authorization", "Bearer " + adminToken)
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        HttpResponse<String> preexistingApproval = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, preexistingRequestId, "approve", "Approved."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, preexistingApproval.statusCode(), preexistingApproval.body());
        assertPackageGrantState(preexistingRequestId, preexistingId, groupId, "PREEXISTING", "UNVERIFIED", 0);

        String permanentUsername = "package-permanent-requester-" + UUID.randomUUID();
        createEnabledUser(server, adminToken, permanentUsername, password);
        String permanentToken = accessToken(server, clientId, permanentUsername, password);
        String permanentId = subjectOf(permanentToken);
        HttpResponse<String> permanentSubmission = HttpClient.newHttpClient().send(
                requestSubmission(requestsEndpoint, permanentToken, """
                        {"entitlementId":"%s","justification":"Permanent access approved by policy.","permanent":true}
                        """.formatted(entitlementId)), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, permanentSubmission.statusCode(), permanentSubmission.body());
        String permanentRequestId = responseId(permanentSubmission.body());
        HttpResponse<String> permanentApproval = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, permanentRequestId, "approve", "Approved."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, permanentApproval.statusCode(), permanentApproval.body());
        assertGroupMembership(server, adminToken, permanentId, groupId);
        assertPackageGrantState(permanentRequestId, permanentId, groupId,
                "CREATED_BY_EXTENSION", "UNVERIFIED", 0);

        String retryUsername = "package-retry-requester-" + UUID.randomUUID();
        createEnabledUser(server, adminToken, retryUsername, password);
        String retryToken = accessToken(server, clientId, retryUsername, password);
        String retryId = subjectOf(retryToken);
        String retryRequestId = submitTemporaryPackageRequest(requestsEndpoint, retryToken, entitlementId);
        URI groupRolesEndpoint = URI.create("http://%s:%d/admin/realms/master/groups/%s/role-mappings/realm"
                .formatted(server.getHost(), server.getMappedPort(8080), groupId));
        HttpResponse<String> groupRoles = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(groupRolesEndpoint).header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, groupRoles.statusCode());
        assertEquals(204, HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(groupRolesEndpoint).header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(groupRoles.body())).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        HttpResponse<String> failedProvisioning;
        try {
            failedProvisioning = HttpClient.newHttpClient().send(
                    requestDecision(accessRequestsEndpoint, approverToken, retryRequestId, "approve", "Approved."),
                    HttpResponse.BodyHandlers.ofString());
        } finally {
            assertEquals(204, HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(groupRolesEndpoint).header("Authorization", "Bearer " + adminToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(groupRoles.body())).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertEquals(200, failedProvisioning.statusCode(), failedProvisioning.body());
        assertTrue(failedProvisioning.body().contains("\"provisioningStatus\":\"FAILED\""));
        assertNoGrantForRequest(retryRequestId);
        URI retryEndpoint = URI.create(accessRequestsEndpoint + "/admin/requests/" + retryRequestId
                + "/provisioning/retry");
        rejectGrantAuthorizationForRequest(retryRequestId);
        HttpResponse<String> rolledBackRetry;
        try {
            rolledBackRetry = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(retryEndpoint).header("Authorization", "Bearer " + managerToken)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        } finally {
            allowGrantAuthorizationForRequest();
        }
        assertTrue(rolledBackRetry.statusCode() >= 400,
                "A failed grant authorization must roll back the provisioning retry");
        assertNoGrantForRequest(retryRequestId);
        assertProvisioningResultAndAuditEvents(retryRequestId, "FAILED", "PROVISIONING_FAILED",
                subjectOf(approverToken));
        assertNoGroupMembership(server, adminToken, retryId, groupId);
        HttpResponse<String> successfulRetry = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(retryEndpoint).header("Authorization", "Bearer " + managerToken)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, successfulRetry.statusCode(), successfulRetry.body());
        assertTrue(successfulRetry.body().contains("\"provisioningStatus\":\"SUCCEEDED\""));
        assertGroupMembership(server, adminToken, retryId, groupId);
        assertPackageGrantState(retryRequestId, retryId, groupId, "CREATED_BY_EXTENSION", "AUTHORIZED", 1);

        String rollbackUsername = "package-rollback-requester-" + UUID.randomUUID();
        createEnabledUser(server, adminToken, rollbackUsername, password);
        String rollbackToken = accessToken(server, clientId, rollbackUsername, password);
        String rollbackId = subjectOf(rollbackToken);
        String rollbackRequestId = submitTemporaryPackageRequest(requestsEndpoint, rollbackToken, entitlementId);
        rejectGrantAuthorizationForRequest(rollbackRequestId);
        HttpResponse<String> failedApproval;
        try {
            failedApproval = HttpClient.newHttpClient().send(
                    requestDecision(accessRequestsEndpoint, approverToken, rollbackRequestId, "approve", "Approved."),
                    HttpResponse.BodyHandlers.ofString());
        } finally {
            allowGrantAuthorizationForRequest();
        }
        assertTrue(failedApproval.statusCode() >= 400,
                "A failed grant authorization must roll back the whole approval transaction");
        assertNoGrantForRequest(rollbackRequestId);
        assertPendingRequestAndCreatedAuditEvent(rollbackRequestId, entitlementId, rollbackId,
                "Temporary access needed.", 3600L);
        assertNoGroupMembership(server, adminToken, rollbackId, groupId);
    }

    private String submitTemporaryPackageRequest(URI endpoint, String requesterToken, String entitlementId)
            throws Exception {
        HttpResponse<String> created = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, requesterToken, """
                        {"entitlementId":"%s","justification":"Temporary access needed.","durationSeconds":3600}
                        """.formatted(entitlementId)), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        return responseId(created.body());
    }

    private void assertPackageGrantState(String requestId, String requesterId, String groupId,
            String origin, String revocationState, long version) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     select REQUESTER_ID, RESOURCE_TYPE, RESOURCE_ID, DELIVERY_GROUP_ID,
                            GRANT_ORIGIN, REVOCATION_STATE, EXPIRES_TIMESTAMP, VERSION
                       from AR_ACCESS_GRANT where REQUEST_ID = ?
                     """)) {
            statement.setString(1, requestId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next(), "Successful package provisioning must persist one grant");
                assertEquals(requesterId, row.getString("REQUESTER_ID"));
                assertEquals("GROUP", row.getString("RESOURCE_TYPE"));
                assertEquals(groupId, row.getString("RESOURCE_ID"));
                assertEquals(groupId, row.getString("DELIVERY_GROUP_ID"));
                assertEquals(origin, row.getString("GRANT_ORIGIN"));
                assertEquals(revocationState, row.getString("REVOCATION_STATE"));
                if ("AUTHORIZED".equals(revocationState)) {
                    assertTrue(row.getLong("EXPIRES_TIMESTAMP") > 0);
                } else {
                    assertNull(row.getObject("EXPIRES_TIMESTAMP", Long.class));
                }
                assertEquals(version, row.getLong("VERSION"));
                assertFalse(row.next());
            }
        }
    }

    private void rejectGrantAuthorizationForRequest(String requestId) throws SQLException {
        String safeRequestId = UUID.fromString(requestId).toString();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("alter table AR_ACCESS_GRANT add constraint CK_AR_GRANT_AUTHORIZATION_IT "
                    + "check (REQUEST_ID <> '" + safeRequestId + "' or REVOCATION_STATE <> 'AUTHORIZED')");
        }
    }

    private void allowGrantAuthorizationForRequest() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("alter table AR_ACCESS_GRANT drop constraint CK_AR_GRANT_AUTHORIZATION_IT");
        }
    }

    private void assertNoGroupMembership(GenericContainer<?> server, String adminToken, String userId, String groupId)
            throws Exception {
        URI endpoint = URI.create("http://%s:%d/admin/realms/master/users/%s/groups"
                .formatted(server.getHost(), server.getMappedPort(8080), userId));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("\"id\":\"" + groupId + "\""),
                "Rolled-back grant authorization must also roll back the group membership");
    }

    private int accessPackageGroupCount(GenericContainer<?> server, String adminToken) throws Exception {
        URI groupsEndpoint = URI.create("http://%s:%d/admin/realms/master/groups?search=AR_PKG_&exact=false&max=100"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<String> groups = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(groupsEndpoint).header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, groups.statusCode());
        return new ObjectMapper().readTree(groups.body()).size();
    }

    private void assertAccessPackageSurvivesRestart(GenericContainer<?> server) throws Exception {
        assertNotNull(accessPackageEntitlementId);
        assertNotNull(accessPackageGroupId);
        String adminToken = accessToken(server, "admin-cli");
        URI entitlementEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/entitlements/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), accessPackageEntitlementId));
        HttpResponse<String> entitlement = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementEndpoint)
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, entitlement.statusCode());
        assertEquals(accessPackageGroupId, new ObjectMapper().readTree(entitlement.body()).path("resourceId").asText());

        URI groupEndpoint = URI.create("http://%s:%d/admin/realms/master/groups/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), accessPackageGroupId));
        HttpResponse<Void> group = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(groupEndpoint)
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, group.statusCode());
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement binding = connection.prepareStatement(
                     "select group_id from ar_access_package where entitlement_id = ?")) {
            binding.setString(1, accessPackageEntitlementId);
            try (ResultSet row = binding.executeQuery()) {
                assertTrue(row.next());
                assertEquals(accessPackageGroupId, row.getString(1));
            }
        }
    }

    private void assertBindingFailureRollsBackKeycloakGroup(GenericContainer<?> server, String adminToken,
            URI packagesEndpoint, String submission) throws Exception {
        int groupsBefore = accessPackageGroupCount(server, adminToken);
        long entitlementsBefore;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from ar_entitlement")) {
            assertTrue(rows.next());
            entitlementsBefore = rows.getLong(1);
        }

        // Force the binding insert to fail after Keycloak has created the group.
        // The disposable test database lets us verify the transaction boundary without a test-only production hook.
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("alter table ar_access_package add constraint jit_binding_fail_test check (false)");
        }
        try {
            HttpResponse<Void> failed = HttpClient.newHttpClient().send(HttpRequest.newBuilder(packagesEndpoint)
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(submission)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertTrue(failed.statusCode() >= 400, "A failed package binding must not return success");
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("alter table ar_access_package drop constraint jit_binding_fail_test");
            }
        }
        assertEquals(groupsBefore, accessPackageGroupCount(server, adminToken),
                "A failed binding must roll back the Keycloak group creation");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from ar_entitlement")) {
            assertTrue(rows.next());
            assertEquals(entitlementsBefore, rows.getLong(1),
                    "A failed binding must also roll back the entitlement");
        }
    }

    private void assertEntitlementCatalogAdministration(GenericContainer<?> server) throws Exception {
        URI entitlementEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/entitlements"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        URI capabilityEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/capabilities"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        URI referenceEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/references?type=REALM_ROLE"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        String adminToken = accessToken(server, "admin-cli");
        enableNativeAdminEvents(server, adminToken);

        HttpResponse<Void> unauthenticatedResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementEndpoint).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, unauthenticatedResponse.statusCode());

        HttpResponse<String> globalAdministratorCapability = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(capabilityEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, globalAdministratorCapability.statusCode());
        assertTrue(globalAdministratorCapability.body().contains("\"canManageCatalog\":true"));
        assertTrue(globalAdministratorCapability.body().contains("\"canManageNotifications\":true"));
        assertTrue(globalAdministratorCapability.body().contains("\"canManageProvisioningFailures\":true"));

        String delegatedClientId = "catalog-delegated-" + UUID.randomUUID();
        createDirectAccessClient(server, adminToken, delegatedClientId);
        String delegatedUsername = "catalog-delegated-user-" + UUID.randomUUID();
        String delegatedPassword = "catalog-delegated-password";
        createEnabledUser(server, adminToken, delegatedUsername, delegatedPassword);
        String delegatedUserToken = accessToken(server, delegatedClientId, delegatedUsername, delegatedPassword);
        assignRealmManagementRoles(server, adminToken, subjectOf(delegatedUserToken), "view-realm");
        delegatedUserToken = accessToken(server, delegatedClientId, delegatedUsername, delegatedPassword);

        HttpResponse<Void> delegatedManagerResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementEndpoint)
                        .header("Authorization", "Bearer " + delegatedUserToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, delegatedManagerResponse.statusCode());
        HttpResponse<Void> delegatedReferenceResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(referenceEndpoint)
                        .header("Authorization", "Bearer " + delegatedUserToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, delegatedReferenceResponse.statusCode());

        String roleOnlyUsername = "catalog-role-only-user-" + UUID.randomUUID();
        String roleOnlyPassword = "catalog-role-only-password";
        createEnabledUser(server, adminToken, roleOnlyUsername, roleOnlyPassword);
        String roleOnlyToken = accessToken(server, delegatedClientId, roleOnlyUsername, roleOnlyPassword);
        ensureRealmRoleAndAssignToUser(
                server,
                adminToken,
                subjectOf(roleOnlyToken),
                ACCESS_REQUEST_MANAGER_ROLE);
        roleOnlyToken = accessToken(server, delegatedClientId, roleOnlyUsername, roleOnlyPassword);
        HttpResponse<Void> roleOnlyResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementEndpoint)
                        .header("Authorization", "Bearer " + roleOnlyToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, roleOnlyResponse.statusCode());

        ensureRealmRoleAndAssignToUser(
                server,
                adminToken,
                subjectOf(delegatedUserToken),
                ACCESS_REQUEST_MANAGER_ROLE);
        String managerToken = accessToken(server, delegatedClientId, delegatedUsername, delegatedPassword);
        HttpResponse<String> delegatedManagerCapability = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(capabilityEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, delegatedManagerCapability.statusCode());
        assertTrue(delegatedManagerCapability.body().contains("\"canManageCatalog\":true"));
        assertTrue(delegatedManagerCapability.body().contains("\"canManageNotifications\":true"));
        assertTrue(delegatedManagerCapability.body().contains("\"canManageProvisioningFailures\":true"));
        assertNotificationDeliveryAdministration(server, managerToken);
        String targetRoleId = createRealmRole(server, adminToken, "catalog-target-" + UUID.randomUUID());
        String approverRoleId = createRealmRole(server, adminToken, "catalog-approver-" + UUID.randomUUID());
        String replacementApproverRoleId = createRealmRole(server, adminToken, "catalog-approver-new-" + UUID.randomUUID());
        ClientRole clientRole = createClientRole(server, adminToken, "catalog-client-target-" + UUID.randomUUID());
        String groupId = createGroup(server, adminToken, "Catalog-Group-Target-" + UUID.randomUUID());
        assertKeycloakReferenceIsListed(server, managerToken, "REALM_ROLE", "catalog-target", targetRoleId);
        assertKeycloakReferenceIsListed(server, managerToken, "CLIENT_ROLE", "catalog-client-target", clientRole.roleId());
        assertKeycloakReferenceIsListed(server, managerToken, "GROUP", "catalog-group-target", groupId);
        assertKeycloakReferenceIsListed(server, managerToken, "GROUP", "CATALOG-GROUP-TARGET", groupId);
        assertBoundedReferenceLookupAndSelectedId(server, managerToken, targetRoleId);
        String description = "Read-only access to the catalog-managed finance report.";
        HttpResponse<String> creationResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "resourceType":"REALM_ROLE",
                                  "resourceId":"%s",
                                  "displayName":"Catalog Finance Reader",
                                  "description":"%s",
                                  "riskLevel":"HIGH",
                                  "approverRoleId":"%s",
                                  "defaultDurationSeconds":14400,
                                  "maxDurationSeconds":43200,
                                  "allowPermanent":false
                                }
                                """.formatted(targetRoleId, description, approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, creationResponse.statusCode());
        assertTrue(creationResponse.body().contains("\"requestable\":false"));
        assertTrue(creationResponse.body().contains("\"defaultDurationSeconds\":14400"));
        assertTrue(creationResponse.body().contains("\"maxDurationSeconds\":43200"));
        assertTrue(creationResponse.body().contains("\"allowPermanent\":false"));
        String entitlementId = responseId(creationResponse.body());
        HttpResponse<String> defaultEntitlementPage = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertDefaultJsonPage(defaultEntitlementPage);
        assertTrue(defaultEntitlementPage.body().contains("\"id\":\"" + entitlementId + "\""));

        URI entitlementByIdEndpoint = URI.create(entitlementEndpoint + "/" + entitlementId);
        HttpResponse<String> updateResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementByIdEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "displayName":"Catalog Finance Reader",
                                  "description":"%s",
                                  "riskLevel":"MEDIUM",
                                  "approverRoleId":"%s",
                                  "requestable":true,
                                  "version":0,
                                  "defaultDurationSeconds":604800,
                                  "maxDurationSeconds":2592000,
                                  "allowPermanent":true
                                }
                                """.formatted(description, replacementApproverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updateResponse.statusCode());
        assertTrue(updateResponse.body().contains("\"requestable\":true"));
        assertTrue(updateResponse.body().contains("\"version\":1"));
        assertTrue(updateResponse.body().contains("\"defaultDurationSeconds\":604800"));
        assertTrue(updateResponse.body().contains("\"maxDurationSeconds\":2592000"));
        assertTrue(updateResponse.body().contains("\"allowPermanent\":true"));

        HttpResponse<Void> invalidDurationResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementByIdEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "displayName":"Catalog Finance Reader",
                                  "description":"%s",
                                  "riskLevel":"MEDIUM",
                                  "approverRoleId":"%s",
                                  "requestable":true,
                                  "version":1,
                                  "defaultDurationSeconds":2592001,
                                  "maxDurationSeconds":2592000,
                                  "allowPermanent":true
                                }
                                """.formatted(description, replacementApproverRoleId)))
                        .build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(400, invalidDurationResponse.statusCode());

        HttpResponse<Void> staleUpdateResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementByIdEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "displayName":"Catalog Finance Reader",
                                  "description":"%s",
                                  "riskLevel":"HIGH",
                                  "approverRoleId":"%s",
                                  "requestable":false,
                                  "version":0
                                }
                                """.formatted(description, approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(409, staleUpdateResponse.statusCode());
        assertEntitlementAuditEvents(entitlementId, subjectOf(managerToken));

        HttpResponse<String> deactivationResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(entitlementByIdEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "displayName":"Catalog Finance Reader",
                                  "description":"%s",
                                  "riskLevel":"MEDIUM",
                                  "approverRoleId":"%s",
                                  "requestable":false,
                                  "version":1
                                }
                                """.formatted(description, replacementApproverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deactivationResponse.statusCode());
        assertTrue(deactivationResponse.body().contains("\"requestable\":false"));
        assertCatalogNativeAdminEvents(server, adminToken, entitlementId, subjectOf(managerToken),
                approverRoleId, replacementApproverRoleId);

        String otherRealmName = "catalog-other-realm-" + UUID.randomUUID();
        createRealm(server, adminToken, otherRealmName);
        URI otherRealmEndpoint = URI.create("http://%s:%d/realms/%s/access-requests/admin/entitlements"
                .formatted(server.getHost(), server.getMappedPort(8080), otherRealmName));
        HttpResponse<Void> crossRealmResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(otherRealmEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, crossRealmResponse.statusCode());
    }

    private void enableNativeAdminEvents(GenericContainer<?> server, String adminToken) throws Exception {
        URI realmEndpoint = URI.create("http://%s:%d/admin/realms/master"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(realmEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"adminEventsEnabled\":true}"))
                        .build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private void assertCatalogNativeAdminEvents(GenericContainer<?> server, String adminToken,
            String entitlementId, String actorId, String originalApproverRoleId,
            String replacementApproverRoleId) throws Exception {
        URI eventsEndpoint = URI.create("http://%s:%d/admin/realms/master/admin-events?max=100"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(eventsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        List<JsonNode> events = java.util.stream.StreamSupport.stream(
                        new ObjectMapper().readTree(response.body()).spliterator(), false)
                .filter(event -> "ACCESS_REQUEST_ENTITLEMENT".equals(event.path("resourceType").asText()))
                .filter(event -> ("access-requests/entitlements/" + entitlementId)
                        .equals(event.path("resourcePath").asText()))
                .toList();
        assertEquals(3, events.size(), "A rejected stale update must not produce an Admin Event.");
        assertTrue(events.stream().allMatch(event -> actorId.equals(event.path("authDetails").path("userId").asText())));
        assertTrue(events.stream().allMatch(event -> !event.hasNonNull("representation")),
                "The native audit stream must not contain the full entitlement representation.");
        assertTrue(events.stream().anyMatch(event -> "CREATE".equals(event.path("operationType").asText())
                && "false".equals(event.path("details").path("requestable").asText())
                && "HIGH".equals(event.path("details").path("riskLevel").asText())
                && originalApproverRoleId.equals(event.path("details").path("approverRoleId").asText())));
        assertTrue(events.stream().anyMatch(event -> "UPDATE".equals(event.path("operationType").asText())
                && "true".equals(event.path("details").path("requestable").asText())
                && "MEDIUM".equals(event.path("details").path("riskLevel").asText())
                && replacementApproverRoleId.equals(event.path("details").path("approverRoleId").asText())));
        assertTrue(events.stream().anyMatch(event -> "UPDATE".equals(event.path("operationType").asText())
                && "false".equals(event.path("details").path("requestable").asText())
                && "MEDIUM".equals(event.path("details").path("riskLevel").asText())
                && replacementApproverRoleId.equals(event.path("details").path("approverRoleId").asText())));
    }

    private void assertAdministrativeAuditEventSearch(GenericContainer<?> server) throws Exception {
        String adminToken = accessToken(server, "admin-cli");
        URI endpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/events"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(401, anonymous.statusCode());

        String username = "audit-nonmanager-" + UUID.randomUUID();
        String password = "audit-nonmanager-password";
        createEnabledUser(server, adminToken, username, password);
        String nonManagerToken = accessToken(server, "admin-cli", username, password);
        HttpResponse<Void> denied = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + nonManagerToken)
                        .GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(403, denied.statusCode());

        String eventId;
        String requestId;
        String actorId;
        String eventType;
        Instant occurredAt;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     select ID, REQUEST_ID, ACTOR_ID, EVENT_TYPE, EVENT_TIMESTAMP
                       from AR_ACCESS_REQUEST_HISTORY
                      where REALM_ID = ?
                      order by EVENT_TIMESTAMP desc
                      limit 1
                     """)) {
            statement.setString(1, masterRealmId(connection));
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "The earlier real workflows must have persisted audit events.");
                eventId = result.getString("ID");
                requestId = result.getString("REQUEST_ID");
                actorId = result.getString("ACTOR_ID");
                eventType = result.getString("EVENT_TYPE");
                occurredAt = Instant.ofEpochMilli(result.getLong("EVENT_TIMESTAMP"));
            }
        }

        URI filtered = URI.create(endpoint + "?page=0&size=20&requestId=" + requestId
                + "&actorId=" + actorId + "&type=" + eventType
                + "&from=" + occurredAt + "&to=" + occurredAt);
        HttpResponse<String> matching = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(filtered)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, matching.statusCode(), matching.body());
        JsonNode filteredPage = new ObjectMapper().readTree(matching.body());
        assertTrue(filteredPage.path("total").asInt() >= 1);
        assertTrue(java.util.stream.StreamSupport.stream(filteredPage.path("items").spliterator(), false)
                .anyMatch(event -> eventId.equals(event.path("id").asText())));
        HttpResponse<String> defaultAuditPage = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertDefaultJsonPage(defaultAuditPage);

        URI fractionalWindow = URI.create(endpoint + "?requestId=" + requestId
                + "&from=" + occurredAt.plusNanos(1)
                + "&to=" + occurredAt.plusNanos(999_999));
        HttpResponse<String> fractionalResult = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(fractionalWindow)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, fractionalResult.statusCode(), fractionalResult.body());
        assertEquals(0, new ObjectMapper().readTree(fractionalResult.body()).path("total").asInt());

        for (JsonNode item : filteredPage.path("items")) {
            assertFalse(item.has("comment"));
            assertFalse(item.has("metadata"));
            assertFalse(item.has("justification"));
        }

        URI detailEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/requests/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), requestId));
        HttpResponse<String> detail = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(detailEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, detail.statusCode(), detail.body());
        JsonNode detailBody = new ObjectMapper().readTree(detail.body());
        assertEquals(requestId, detailBody.path("id").asText());
        assertFalse(detailBody.path("requesterId").asText().isBlank());
        assertFalse(detailBody.path("decisionStatus").asText().isBlank());
        assertFalse(detailBody.path("provisioningStatus").asText().isBlank());
        assertTrue(detailBody.path("history").isArray());
        assertEquals(0, detailBody.path("historyPage").asInt());
        assertEquals(20, detailBody.path("historySize").asInt());
        assertTrue(detailBody.path("historyTotal").asLong() >= detailBody.path("history").size());
        assertTrue(java.util.stream.StreamSupport.stream(detailBody.path("history").spliterator(), false)
                .anyMatch(item -> eventType.equals(item.path("type").asText())
                        && actorId.equals(item.path("actorId").asText())));
        HttpResponse<String> detailFirst = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(detailEndpoint + "?historyPage=0&historySize=1"))
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> detailSecond = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(detailEndpoint + "?historyPage=1&historySize=1"))
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, detailFirst.statusCode());
        assertEquals(200, detailSecond.statusCode());
        JsonNode detailFirstBody = new ObjectMapper().readTree(detailFirst.body());
        JsonNode detailSecondBody = new ObjectMapper().readTree(detailSecond.body());
        assertEquals(1, detailFirstBody.path("history").size());
        assertEquals(1, detailSecondBody.path("history").size());
        assertEquals(detailFirstBody.path("historyTotal").asLong(), detailSecondBody.path("historyTotal").asLong());
        assertFalse(detailFirstBody.path("history").get(0).equals(detailSecondBody.path("history").get(0)));
        HttpResponse<Void> invalidDetailPage = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(detailEndpoint + "?historySize=101"))
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(400, invalidDetailPage.statusCode());
        HttpResponse<Void> deniedDetail = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(detailEndpoint)
                        .header("Authorization", "Bearer " + nonManagerToken)
                        .GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(403, deniedDetail.statusCode());

        HttpResponse<String> first = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(endpoint + "?page=0&size=1"))
                        .header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(endpoint + "?page=1&size=1"))
                        .header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        JsonNode firstPage = new ObjectMapper().readTree(first.body());
        JsonNode secondPage = new ObjectMapper().readTree(second.body());
        assertTrue(firstPage.path("total").asInt() > 1);
        assertEquals(1, firstPage.path("items").size());
        assertEquals(1, secondPage.path("items").size());
        assertFalse(firstPage.path("items").get(0).path("id").asText()
                .equals(secondPage.path("items").get(0).path("id").asText()));

        HttpResponse<Void> invalid = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(endpoint + "?page=0&size=0"))
                        .header("Authorization", "Bearer " + adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(400, invalid.statusCode());
        for (String query : List.of("type=NOT_AN_EVENT", "from=not-a-date",
                "from=%2B1000000000-12-31T23:59:59Z", "to=-1000000000-01-01T00:00:00Z",
                "from=2026-09-25T00:00:00Z&to=2026-09-24T00:00:00Z")) {
            HttpResponse<Void> invalidFilter = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                            .header("Authorization", "Bearer " + adminToken)
                            .GET().build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(400, invalidFilter.statusCode(), query);
        }

        String otherRealm = "audit-other-realm-" + UUID.randomUUID();
        createRealm(server, adminToken, otherRealm);
        // Keep the isolation assertion independent of an admin token issued before the realm existed.
        String freshAdminToken = accessToken(server, "admin-cli");
        HttpResponse<String> isolated = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://%s:%d/realms/%s/access-requests/admin/events"
                                .formatted(server.getHost(), server.getMappedPort(8080), otherRealm)))
                        .header("Authorization", "Bearer " + freshAdminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, isolated.statusCode());
        assertEquals(0, new ObjectMapper().readTree(isolated.body()).path("total").asInt());
        HttpResponse<Void> isolatedDetail = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://%s:%d/realms/%s/access-requests/admin/requests/%s"
                                .formatted(server.getHost(), server.getMappedPort(8080), otherRealm, requestId)))
                        .header("Authorization", "Bearer " + freshAdminToken)
                        .GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(404, isolatedDetail.statusCode());
    }

    private void assertNotificationDeliveryAdministration(GenericContainer<?> server, String managerToken) throws Exception {
        URI deliveryEndpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/notification-deliveries"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        URI summaryEndpoint = URI.create(deliveryEndpoint + "/summary");
        String deliveryId = UUID.randomUUID().toString();
        insertFailedNotificationDelivery(deliveryId);

        HttpResponse<Void> unauthenticatedResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(deliveryEndpoint).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, unauthenticatedResponse.statusCode());

        HttpResponse<String> failedDeliveries = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(deliveryEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, failedDeliveries.statusCode());
        assertTrue(failedDeliveries.body().contains("\"id\":\"" + deliveryId + "\""));
        assertTrue(failedDeliveries.body().contains("\"attemptCount\":10"));
        assertFalse(failedDeliveries.body().contains("@"),
                "Administrative delivery operations must not expose recipient e-mail addresses.");

        HttpResponse<String> summary = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(summaryEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, summary.statusCode());
        assertTrue(new ObjectMapper().readTree(summary.body()).path("failed").asInt() >= 1);

        URI retryEndpoint = URI.create(deliveryEndpoint + "/" + deliveryId + "/retry");
        HttpResponse<Void> retried = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(retryEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, retried.statusCode());

        HttpResponse<Void> retryConflict = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(retryEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(409, retryConflict.statusCode());
    }

    private void insertFailedNotificationDelivery(String deliveryId) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     insert into AR_NOTIFICATION_OUTBOX (
                         ID, DELIVERY_KEY, EVENT_ID, REQUEST_ID, ENTITLEMENT_ID, REALM_ID,
                         RECIPIENT_ID, RECIPIENT_TYPE, NOTIFICATION_TYPE, STATE,
                         ATTEMPT_COUNT, NEXT_ATTEMPT_TIMESTAMP, LAST_ATTEMPT_TIMESTAMP, VERSION)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, deliveryId);
            statement.setString(2, "manual-test-" + deliveryId);
            statement.setString(3, UUID.randomUUID().toString());
            statement.setString(4, UUID.randomUUID().toString());
            statement.setString(5, UUID.randomUUID().toString());
            statement.setString(6, masterRealmId(connection));
            statement.setString(7, "recipient-" + UUID.randomUUID());
            statement.setString(8, "USER");
            statement.setString(9, "REQUEST_SUBMITTED");
            statement.setString(10, "FAILED");
            statement.setInt(11, 10);
            statement.setLong(12, now);
            statement.setLong(13, now);
            statement.setLong(14, 0);
            assertEquals(1, statement.executeUpdate());
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

    private void assertAccountThemeMessagesAreServedFromTheDeployedProviderJar(GenericContainer<?> server)
            throws Exception {
        String adminToken = accessToken(server, "admin-cli");
        URI realmEndpoint = URI.create("http://%s:%d/admin/realms/master"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> themeUpdateResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(realmEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"accountTheme\":\"access-requests\"}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, themeUpdateResponse.statusCode());

        URI messagesEndpoint = URI.create("http://%s:%d/resources/master/account/en"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<String> messagesResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(messagesEndpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, messagesResponse.statusCode());
        assertTrue(messagesResponse.body().contains("\"key\":\"accessRequestsRequestAccess\""));
        assertTrue(messagesResponse.body().contains("\"key\":\"accessRequestsMyRequests\""));
        assertTrue(messagesResponse.body().contains("\"key\":\"accessRequestsApprovals\""));
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
        assertDefaultJsonPage(catalogResponse);
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

    private void assertRequesterCanListViewAndCancelOnlyOwnRequests(GenericContainer<?> server) throws Exception {
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
        HttpResponse<String> defaultRequestPage = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(myRequestsEndpoint)
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertDefaultJsonPage(defaultRequestPage);
        assertTrue(defaultRequestPage.body().contains(firstRequestId));
        assertTrue(defaultRequestPage.body().contains(secondRequestId));

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

        HttpResponse<String> otherRequesterDetailResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "/" + firstRequestId))
                        .header("Authorization", "Bearer " + otherRequesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, otherRequesterDetailResponse.statusCode());
        assertError(otherRequesterDetailResponse.body(), "REQUEST_NOT_FOUND", firstRequestId);

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

        HttpResponse<String> requesterDetailResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(myRequestsEndpoint + "/" + firstRequestId))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, requesterDetailResponse.statusCode());
        assertTrue(requesterDetailResponse.body().contains("\"id\":\"" + firstRequestId + "\""));
        assertTrue(requesterDetailResponse.body().contains("\"entitlementId\":\"" + firstEntitlementId + "\""));
        assertTrue(requesterDetailResponse.body().contains(
                "\"justification\":\"I need access to the first Finance Portal report.\""));
        assertTrue(requesterDetailResponse.body().contains("\"decisionStatus\":\"CANCELED\""));
        int createdEvent = requesterDetailResponse.body().indexOf("\"type\":\"REQUEST_CREATED\"");
        int canceledEvent = requesterDetailResponse.body().indexOf("\"type\":\"REQUEST_CANCELED\"");
        assertTrue(createdEvent >= 0 && createdEvent < canceledEvent,
                "The requester detail must return the immutable history in chronological order.");

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
        URI pendingRequestsEndpoint = URI.create(accessRequestsEndpoint + "/pending");
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

        String managerUsername = "provisioning-retry-manager-" + UUID.randomUUID();
        String managerPassword = "provisioning-retry-manager-password";
        createEnabledUser(server, adminToken, managerUsername, managerPassword);
        String managerToken = accessToken(server, clientId, managerUsername, managerPassword);
        assignRealmManagementRoles(server, adminToken, subjectOf(managerToken), "view-realm");
        ensureRealmRoleAndAssignToUser(
                server, adminToken, subjectOf(managerToken), ACCESS_REQUEST_MANAGER_ROLE);
        managerToken = accessToken(server, clientId, managerUsername, managerPassword);

        String provisionedRoleId = createRealmRole(
                server, adminToken, "finance-reader-" + UUID.randomUUID());
        String entitlementId = UUID.randomUUID().toString();
        insertEntitlement(entitlementId, "REALM_ROLE", provisionedRoleId, approverRoleId, true);
        String approvalRequestJustification = "I need access to Finance Portal reports for the project.";

        HttpResponse<String> approvedRequestResponse = HttpClient.newHttpClient().send(
                requestSubmission(
                        requestsEndpoint,
                        requesterToken,
                        """
                        {"entitlementId":"%s","justification":"%s","durationSeconds":14400}
                        """.formatted(entitlementId, approvalRequestJustification)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, approvedRequestResponse.statusCode());
        String approvedRequestId = responseId(approvedRequestResponse.body());

        HttpResponse<Void> unauthenticatedQueueResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(pendingRequestsEndpoint).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, unauthenticatedQueueResponse.statusCode());

        HttpResponse<String> requesterQueueResponse = HttpClient.newHttpClient().send(
                pendingRequests(pendingRequestsEndpoint, requesterToken),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, requesterQueueResponse.statusCode());
        assertTrue(requesterQueueResponse.body().contains("\"total\":0"));
        assertFalse(requesterQueueResponse.body().contains(approvedRequestId));

        HttpResponse<String> unauthorizedQueueResponse = HttpClient.newHttpClient().send(
                pendingRequests(pendingRequestsEndpoint, unauthorizedToken),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, unauthorizedQueueResponse.statusCode());
        assertTrue(unauthorizedQueueResponse.body().contains("\"total\":0"));
        assertFalse(unauthorizedQueueResponse.body().contains(approvedRequestId));

        HttpResponse<String> approverQueueResponse = HttpClient.newHttpClient().send(
                pendingRequests(pendingRequestsEndpoint, approverToken),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approverQueueResponse.statusCode());
        assertDefaultJsonPage(approverQueueResponse);
        assertTrue(approverQueueResponse.body().contains("\"total\":1"));
        assertTrue(approverQueueResponse.body().contains(approvedRequestId));
        assertTrue(approverQueueResponse.body().contains("\"requesterId\":\""
                + subjectOf(requesterToken) + "\""));
        assertTrue(approverQueueResponse.body().contains("\"entitlementId\":\"" + entitlementId + "\""));
        assertTrue(approverQueueResponse.body().contains("\"riskLevel\":\"LOW\""));
        assertTrue(approverQueueResponse.body().contains("\"justification\":\""
                + approvalRequestJustification + "\""));

        HttpResponse<String> invalidQueuePageResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(pendingRequestsEndpoint + "?page=-1"))
                        .header("Authorization", "Bearer " + approverToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidQueuePageResponse.statusCode());
        assertError(invalidQueuePageResponse.body(), "INVALID_REQUEST_QUERY", null);

        HttpResponse<String> missingDecisionPayloadResponse = HttpClient.newHttpClient().send(
                requestDecisionWithoutPayload(accessRequestsEndpoint, approverToken, approvedRequestId, "approve"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, missingDecisionPayloadResponse.statusCode());
        assertError(missingDecisionPayloadResponse.body(), "INVALID_DECISION_SUBMISSION", approvedRequestId);

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
        assertTrue(approvalResponse.body().contains("\"provisioningStatus\":\"SUCCEEDED\""));
        assertDecisionAndAuditEvent(
                approvedRequestId, "APPROVED", "REQUEST_APPROVED", approverId, approvalComment);
        assertProvisioningAndAuditEvents(approvedRequestId, approverId);
        assertRealmRoleAssigned(server, adminToken, subjectOf(requesterToken), provisionedRoleId);
        assertProvisioningRetryEndpoint(accessRequestsEndpoint, managerToken, approvedRequestId);
        assertClientRoleGroupAndFailureProvisioning(
                server,
                adminToken,
                accessRequestsEndpoint,
                requestsEndpoint,
                requesterToken,
                approverToken,
                managerToken,
                approverId,
                approverRoleId);
        assertPreexistingGrantAfterApproval(
                server, adminToken, accessRequestsEndpoint, requestsEndpoint,
                requesterToken, approverToken, approverId, approverRoleId);
        assertGrantPersistenceFailureRollsBackRoleAndRequest(
                server, adminToken, accessRequestsEndpoint, requestsEndpoint,
                requesterToken, approverToken, approverRoleId);

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

    private void assertClientRoleGroupAndFailureProvisioning(
            GenericContainer<?> server,
            String adminToken,
            URI accessRequestsEndpoint,
            URI requestsEndpoint,
            String requesterToken,
            String approverToken,
            String managerToken,
            String approverId,
            String approverRoleId) throws Exception {
        String requesterId = subjectOf(requesterToken);
        ClientRole clientRole = createClientRole(server, adminToken, "finance-client-role-" + UUID.randomUUID());
        String clientRoleEntitlementId = UUID.randomUUID().toString();
        insertEntitlement(clientRoleEntitlementId, "CLIENT_ROLE", clientRole.roleId(), approverRoleId, true);
        submitAndApprove(
                accessRequestsEndpoint,
                requestsEndpoint,
                requesterToken,
                approverToken,
                approverId,
                clientRoleEntitlementId,
                "I need the client role to work with the Finance Portal.");
        assertClientRoleAssigned(server, adminToken, requesterId, clientRole);

        String groupId = createGroup(server, adminToken, "finance-group-" + UUID.randomUUID());
        String groupEntitlementId = UUID.randomUUID().toString();
        insertEntitlement(groupEntitlementId, "GROUP", groupId, approverRoleId, true);
        submitAndApprove(
                accessRequestsEndpoint,
                requestsEndpoint,
                requesterToken,
                approverToken,
                approverId,
                groupEntitlementId,
                "I need the Finance group to prepare the monthly report.");
        assertGroupMembership(server, adminToken, requesterId, groupId);

        String missingRoleEntitlementId = UUID.randomUUID().toString();
        insertEntitlement(
                missingRoleEntitlementId,
                "REALM_ROLE",
                UUID.randomUUID().toString(),
                approverRoleId,
                true);
        HttpResponse<String> createdResponse = HttpClient.newHttpClient().send(
                requestSubmission(
                        requestsEndpoint,
                        requesterToken,
                        missingRoleEntitlementId,
                        "I need a role that was removed from Keycloak."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, createdResponse.statusCode());
        String requestId = responseId(createdResponse.body());

        HttpResponse<String> approvalResponse = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, requestId, "approve", "Approved."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approvalResponse.statusCode());
        assertTrue(approvalResponse.body().contains("\"decisionStatus\":\"APPROVED\""));
        assertTrue(approvalResponse.body().contains("\"provisioningStatus\":\"FAILED\""));
        assertDecisionAndAuditEvent(requestId, "APPROVED", "REQUEST_APPROVED", approverId, "Approved.");
        assertProvisioningResultAndAuditEvents(
                requestId, "FAILED", "PROVISIONING_FAILED", approverId);
        assertFailedProvisioningAdministration(
                server,
                accessRequestsEndpoint,
                adminToken,
                approverToken,
                managerToken,
                requestId);

        URI retryEndpoint = URI.create(accessRequestsEndpoint
                + "/admin/requests/" + requestId + "/provisioning/retry");
        HttpResponse<String> unauthorizedRetryResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(retryEndpoint)
                        .header("Authorization", "Bearer " + approverToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, unauthorizedRetryResponse.statusCode());

        HttpResponse<String> retriedResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(retryEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, retriedResponse.statusCode());
        assertTrue(retriedResponse.body().contains("\"decisionStatus\":\"APPROVED\""));
        assertTrue(retriedResponse.body().contains("\"provisioningStatus\":\"FAILED\""));
        assertProvisioningRetryAuditEvents(requestId, subjectOf(managerToken));
        assertFailedProvisioningAdministration(
                server, accessRequestsEndpoint, adminToken, approverToken, managerToken, requestId);

        URI missingRetryEndpoint = URI.create(accessRequestsEndpoint
                + "/admin/requests/missing-request/provisioning/retry");
        HttpResponse<String> missingRetryResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(missingRetryEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missingRetryResponse.statusCode());
        assertError(missingRetryResponse.body(), "REQUEST_NOT_FOUND", "missing-request");

        URI closeEndpoint = URI.create(accessRequestsEndpoint
                + "/admin/requests/" + requestId + "/provisioning/close");
        HttpResponse<Void> forbiddenClosure = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(closeEndpoint)
                        .header("Authorization", "Bearer " + approverToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"The role was removed.\"}"))
                        .build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(403, forbiddenClosure.statusCode());

        HttpResponse<String> invalidClosure = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(closeEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"short\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidClosure.statusCode());

        HttpResponse<String> closedResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(closeEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"reason\":\"The Keycloak role was permanently removed.\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, closedResponse.statusCode());
        assertTrue(closedResponse.body().contains("\"closedBy\":\"" + subjectOf(managerToken) + "\""));

        URI auditDetailEndpoint = URI.create(accessRequestsEndpoint + "/admin/requests/" + requestId);
        HttpResponse<String> auditDetail = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(auditDetailEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, auditDetail.statusCode(), auditDetail.body());
        JsonNode auditHistory = new ObjectMapper().readTree(auditDetail.body()).path("history");
        List<JsonNode> failedAttempts = java.util.stream.StreamSupport.stream(auditHistory.spliterator(), false)
                .filter(event -> "PROVISIONING_FAILED".equals(event.path("type").asText()))
                .toList();
        assertTrue(failedAttempts.size() >= 2);
        assertTrue(failedAttempts.stream().allMatch(event -> "RESOURCE_MISSING".equals(
                event.path("failureCode").asText())));
        assertTrue(failedAttempts.stream().noneMatch(event -> event.hasNonNull("closureReason")));
        assertTrue(java.util.stream.StreamSupport.stream(auditHistory.spliterator(), false)
                .anyMatch(event -> "PROVISIONING_CLOSED".equals(event.path("type").asText())
                        && "The Keycloak role was permanently removed.".equals(
                                event.path("closureReason").asText())
                        && subjectOf(managerToken).equals(event.path("actorId").asText())));
        assertTrue(java.util.stream.StreamSupport.stream(auditHistory.spliterator(), false)
                .noneMatch(event -> event.has("comment") || event.has("metadata")));
        assertFalse(auditDetail.body().contains("The configured Keycloak role no longer exists."));

        HttpResponse<String> closedQueue = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(accessRequestsEndpoint + "/admin/provisioning-failures"))
                        .header("Authorization", "Bearer " + managerToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, closedQueue.statusCode());
        assertFalse(closedQueue.body().contains(requestId));

        URI archiveEndpoint = URI.create(accessRequestsEndpoint + "/admin/provisioning-failures?state=CLOSED");
        HttpResponse<Void> forbiddenArchive = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(archiveEndpoint)
                        .header("Authorization", "Bearer " + approverToken)
                        .GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(403, forbiddenArchive.statusCode());
        HttpResponse<String> archive = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(archiveEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, archive.statusCode());
        JsonNode archivedPage = new ObjectMapper().readTree(archive.body());
        JsonNode archivedRequest = null;
        for (JsonNode item : archivedPage.path("items")) {
            if (requestId.equals(item.path("id").asText())) {
                archivedRequest = item;
                break;
            }
        }
        assertNotNull(archivedRequest);
        assertEquals(subjectOf(managerToken), archivedRequest.path("closedBy").asText());
        assertEquals("The Keycloak role was permanently removed.", archivedRequest.path("closureReason").asText());
        assertFalse(archivedRequest.path("closedAt").asText().isBlank());
        assertFalse(archivedRequest.has("justification"));
        assertFalse(archivedRequest.has("failureReason"));
        HttpResponse<String> invalidArchiveState = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(accessRequestsEndpoint + "/admin/provisioning-failures?state=ALL"))
                        .header("Authorization", "Bearer " + managerToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidArchiveState.statusCode());
        assertError(invalidArchiveState.body(), "INVALID_PROVISIONING_FAILURE_QUERY", null);

        HttpResponse<String> closedRetry = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(retryEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, closedRetry.statusCode());
        HttpResponse<String> duplicateClosure = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(closeEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"reason\":\"Another closure reason.\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, duplicateClosure.statusCode());

        HttpResponse<String> requesterHistory = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(accessRequestsEndpoint + "/mine/" + requestId))
                        .header("Authorization", "Bearer " + requesterToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, requesterHistory.statusCode());
        assertTrue(requesterHistory.body().contains("\"type\":\"PROVISIONING_CLOSED\""));
        assertTrue(requesterHistory.body().contains("\"provisioningClosedAt\":"));
        assertFalse(requesterHistory.body().contains("The Keycloak role was permanently removed."));
    }

    private void assertPreexistingGrantAfterApproval(
            GenericContainer<?> server,
            String adminToken,
            URI accessRequestsEndpoint,
            URI requestsEndpoint,
            String requesterToken,
            String approverToken,
            String approverId,
            String approverRoleId) throws Exception {
        String requesterId = subjectOf(requesterToken);
        String roleName = "preexisting-role-" + UUID.randomUUID();
        String roleId = createRealmRole(server, adminToken, roleName);
        String entitlementId = UUID.randomUUID().toString();
        insertEntitlement(entitlementId, "REALM_ROLE", roleId, approverRoleId, true);

        HttpResponse<String> created = HttpClient.newHttpClient().send(
                requestSubmission(requestsEndpoint, requesterToken, entitlementId, "Temporary access needed."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        String requestId = responseId(created.body());
        assertRealmRoleNotAssigned(server, adminToken, requesterId, roleId);

        assertEquals(roleId, ensureRealmRoleAndAssignToUser(server, adminToken, requesterId, roleName));
        assertRealmRoleAssigned(server, adminToken, requesterId, roleId);

        HttpResponse<String> approval = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, requestId, "approve", "Approved."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approval.statusCode());
        assertTrue(approval.body().contains("\"provisioningStatus\":\"SUCCEEDED\""));
        assertProvisioningResultAndAuditEvents(
                requestId, "SUCCEEDED", "PROVISIONING_SUCCEEDED", approverId, "PREEXISTING");
        assertRealmRoleAssigned(server, adminToken, requesterId, roleId);
    }

    private void assertGrantPersistenceFailureRollsBackRoleAndRequest(
            GenericContainer<?> server,
            String adminToken,
            URI accessRequestsEndpoint,
            URI requestsEndpoint,
            String requesterToken,
            String approverToken,
            String approverRoleId) throws Exception {
        String requesterId = subjectOf(requesterToken);
        String roleId = createRealmRole(server, adminToken, "rollback-role-" + UUID.randomUUID());
        String entitlementId = UUID.randomUUID().toString();
        insertEntitlement(entitlementId, "REALM_ROLE", roleId, approverRoleId, true);
        String justification = "Temporary access for rollback verification.";

        HttpResponse<String> created = HttpClient.newHttpClient().send(
                requestSubmission(requestsEndpoint, requesterToken, entitlementId, justification),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        String requestId = responseId(created.body());
        assertRealmRoleNotAssigned(server, adminToken, requesterId, roleId);

        // Force the grant INSERT to fail after Keycloak has attempted the role assignment.
        rejectGrantInsertForRequest(requestId);
        HttpResponse<String> approval;
        try {
            approval = HttpClient.newHttpClient().send(
                    requestDecision(accessRequestsEndpoint, approverToken, requestId, "approve", "Approved."),
                    HttpResponse.BodyHandlers.ofString());
        } finally {
            allowGrantInsertForRequest();
        }
        assertEquals(409, approval.statusCode(),
                "Keycloak maps the injected grant constraint violation to a conflict response.");
        assertRealmRoleNotAssigned(server, adminToken, requesterId, roleId);
        assertPendingRequestAndCreatedAuditEvent(requestId, entitlementId, requesterId, justification, 2_592_000L);
        assertNoGrantForRequest(requestId);

        HttpResponse<String> recoveredApproval = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, requestId, "approve", "Approved."),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, recoveredApproval.statusCode(),
                "The same pending request must succeed after the grant storage fault is removed.");
        assertProvisioningAndAuditEvents(requestId, subjectOf(approverToken));
        assertRealmRoleAssigned(server, adminToken, requesterId, roleId);
    }

    private void rejectGrantInsertForRequest(String requestId) throws SQLException {
        String safeRequestId = UUID.fromString(requestId).toString();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("alter table AR_ACCESS_GRANT add constraint CK_AR_GRANT_ROLLBACK_IT "
                    + "check (REQUEST_ID <> '" + safeRequestId + "')");
        }
    }

    private void allowGrantInsertForRequest() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("alter table AR_ACCESS_GRANT drop constraint CK_AR_GRANT_ROLLBACK_IT");
        }
    }

    private void assertNoGrantForRequest(String requestId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                     "select count(*) from AR_ACCESS_GRANT where REQUEST_ID = ?")) {
            statement.setString(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getLong(1), "A rolled-back approval must not persist a grant.");
            }
        }
    }

    private void assertProvisioningRetryEndpoint(
            URI accessRequestsEndpoint,
            String managerToken,
            String alreadyProvisionedRequestId) throws Exception {
        URI retryEndpoint = URI.create(accessRequestsEndpoint
                + "/admin/requests/" + alreadyProvisionedRequestId + "/provisioning/retry");
        HttpResponse<Void> unauthenticated = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(retryEndpoint)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, unauthenticated.statusCode());

        HttpResponse<String> conflict = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(retryEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, conflict.statusCode());
        assertError(conflict.body(), "INVALID_PROVISIONING_RETRY", alreadyProvisionedRequestId);
    }

    private void assertFailedProvisioningAdministration(
            GenericContainer<?> server,
            URI accessRequestsEndpoint,
            String adminToken,
            String unauthorizedToken,
            String managerToken,
            String failedRequestId) throws Exception {
        URI endpoint = URI.create(accessRequestsEndpoint + "/admin/provisioning-failures");
        HttpResponse<Void> unauthenticated = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, unauthenticated.statusCode());

        HttpResponse<Void> forbidden = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + unauthorizedToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, forbidden.statusCode());

        URI pagedEndpoint = URI.create(endpoint + "?page=0&size=100");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(pagedEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode page = new ObjectMapper().readTree(response.body());
        assertEquals(0, page.path("page").asInt());
        assertEquals(100, page.path("size").asInt());
        assertTrue(page.path("total").asLong() >= 1);
        boolean containsFailedRequest = false;
        for (JsonNode item : page.path("items")) {
            assertEquals("APPROVED", item.path("decisionStatus").asText());
            assertEquals("FAILED", item.path("provisioningStatus").asText());
            assertFalse(item.has("failureReason"), "Internal provisioning failure details must not be exposed.");
            if (failedRequestId.equals(item.path("id").asText())) {
                assertEquals("RESOURCE_MISSING", item.path("failureCode").asText());
            }
            assertFalse(item.has("justification"), "The operational list must not expose requester justification.");
            containsFailedRequest |= failedRequestId.equals(item.path("id").asText());
        }
        assertTrue(containsFailedRequest, "The realm's failed provisioning request must appear in the list.");
        assertFalse(response.body().contains("The configured Keycloak role no longer exists."));

        HttpResponse<String> invalidPage = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(endpoint + "?page=0&size=0"))
                        .header("Authorization", "Bearer " + managerToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidPage.statusCode());
        assertError(invalidPage.body(), "INVALID_PROVISIONING_FAILURE_QUERY", null);

        String otherRealm = "provisioning-failures-" + UUID.randomUUID();
        createRealm(server, adminToken, otherRealm);
        URI otherRealmEndpoint = URI.create("http://%s:%d/realms/%s/access-requests/admin/provisioning-failures"
                .formatted(server.getHost(), server.getMappedPort(8080), otherRealm));
        HttpResponse<Void> crossRealmManager = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(otherRealmEndpoint)
                        .header("Authorization", "Bearer " + managerToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, crossRealmManager.statusCode());
    }

    private void assertProvisioningRetryAuditEvents(String requestId, String actorId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     select EVENT_TYPE, ACTOR_ID
                       from AR_ACCESS_REQUEST_HISTORY
                      where REQUEST_ID = ?
                     """)) {
            statement.setString(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                int starts = 0;
                int failures = 0;
                boolean retryActorRecorded = false;
                while (result.next()) {
                    String eventType = result.getString("EVENT_TYPE");
                    if ("PROVISIONING_STARTED".equals(eventType)) {
                        starts++;
                        retryActorRecorded |= actorId.equals(result.getString("ACTOR_ID"));
                    } else if ("PROVISIONING_FAILED".equals(eventType)) {
                        failures++;
                    }
                }
                assertEquals(2, starts, "Retrying must record a second provisioning start event.");
                assertEquals(2, failures, "A failed retry must retain a separate provisioning failure event.");
                assertTrue(retryActorRecorded, "The retry audit event must identify the administrative actor.");
            }
        }
    }

    private void submitAndApprove(
            URI accessRequestsEndpoint,
            URI requestsEndpoint,
            String requesterToken,
            String approverToken,
            String approverId,
            String entitlementId,
            String justification) throws Exception {
        HttpResponse<String> createdResponse = HttpClient.newHttpClient().send(
                requestSubmission(requestsEndpoint, requesterToken, entitlementId, justification),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, createdResponse.statusCode());
        String requestId = responseId(createdResponse.body());

        String approvalComment = "Approved for provisioning verification.";
        HttpResponse<String> approvalResponse = HttpClient.newHttpClient().send(
                requestDecision(accessRequestsEndpoint, approverToken, requestId, "approve", approvalComment),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approvalResponse.statusCode());
        assertTrue(approvalResponse.body().contains("\"decisionStatus\":\"APPROVED\""));
        assertTrue(approvalResponse.body().contains("\"provisioningStatus\":\"SUCCEEDED\""));
        assertDecisionAndAuditEvent(requestId, "APPROVED", "REQUEST_APPROVED", approverId, approvalComment);
        assertProvisioningAndAuditEvents(requestId, approverId);
    }

    private void assertDefaultJsonPage(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type")
                .map(contentType -> contentType.startsWith("application/json"))
                .orElse(false), "A successful page must use the JSON media type.");
        JsonNode page = new ObjectMapper().readTree(response.body());
        assertEquals(0, page.path("page").asInt(-1));
        assertEquals(20, page.path("size").asInt(-1));
        assertTrue(page.path("items").isArray());
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

    private HttpRequest pendingRequests(URI endpoint, String accessToken) {
        return HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
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

    private HttpRequest requestDecisionWithoutPayload(
            URI accessRequestsEndpoint,
            String accessToken,
            String requestId,
            String decision) {
        return HttpRequest.newBuilder(URI.create(accessRequestsEndpoint + "/" + requestId + "/" + decision))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
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

        HttpResponse<Void> excessiveDurationResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, """
                        {"entitlementId":"%s","justification":"%s","durationSeconds":7862400}
                        """.formatted(entitlementId, justification)),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(400, excessiveDurationResponse.statusCode());
        HttpResponse<Void> unauthorizedPermanentResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, """
                        {"entitlementId":"%s","justification":"%s","permanent":true}
                        """.formatted(entitlementId, justification)),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(400, unauthorizedPermanentResponse.statusCode());

        HttpResponse<String> createdResponse = HttpClient.newHttpClient().send(
                requestSubmission(endpoint, accessToken, """
                        {"entitlementId":"%s","justification":"%s","durationSeconds":7776000}
                        """.formatted(entitlementId, justification)),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(201, createdResponse.statusCode());
        String requestId = responseId(createdResponse.body());
        assertTrue(createdResponse.body().contains("\"entitlementId\":\"" + entitlementId + "\""));
        assertTrue(createdResponse.body().contains("\"decisionStatus\":\"PENDING\""));
        assertTrue(createdResponse.body().contains("\"provisioningStatus\":\"NOT_STARTED\""));
        assertTrue(createdResponse.body().contains("\"durationSeconds\":7776000"));
        assertTrue(createdResponse.body().contains("\"permanent\":false"));
        assertPendingRequestAndCreatedAuditEvent(
                requestId, entitlementId, subjectOf(accessToken), justification, 7_776_000L);

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
            String requestId,
            String entitlementId,
            String requesterId,
            String justification,
            long expectedDurationSeconds) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var requestStatement = connection.prepareStatement("""
                     select REQUESTER_ID, JUSTIFICATION, DECISION_STATUS, PROVISIONING_STATUS,
                            REQUESTED_DURATION_SECONDS, PERMANENT
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
                assertEquals(expectedDurationSeconds, result.getLong("REQUESTED_DURATION_SECONDS"));
                assertFalse(result.getBoolean("PERMANENT"));
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

    private void assertProvisioningAndAuditEvents(String requestId, String approverId) throws SQLException {
        assertProvisioningResultAndAuditEvents(requestId, "SUCCEEDED", "PROVISIONING_SUCCEEDED", approverId);
    }

    private void assertProvisioningResultAndAuditEvents(
            String requestId,
            String provisioningStatus,
            String completionEventType,
            String approverId) throws SQLException {
        assertProvisioningResultAndAuditEvents(
                requestId, provisioningStatus, completionEventType, approverId, "CREATED_BY_EXTENSION");
    }

    private void assertProvisioningResultAndAuditEvents(
            String requestId,
            String provisioningStatus,
            String completionEventType,
            String approverId,
            String expectedGrantOrigin) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var requestStatement = connection.prepareStatement("""
                      select PROVISIONING_STATUS, REQUESTED_DURATION_SECONDS, PERMANENT
                       from AR_ACCESS_REQUEST
                      where ID = ?
                     """)) {
            requestStatement.setString(1, requestId);
            Long requestedDurationSeconds;
            boolean permanent;
            try (ResultSet result = requestStatement.executeQuery()) {
                assertTrue(result.next(), "The provisioned request must be persisted.");
                assertEquals(provisioningStatus, result.getString("PROVISIONING_STATUS"));
                requestedDurationSeconds = result.getObject("REQUESTED_DURATION_SECONDS", Long.class);
                permanent = result.getBoolean("PERMANENT");
                assertFalse(result.next(), "Exactly one provisioned request must be persisted.");
            }

            assertProvisioningAuditEvent(connection, requestId, "PROVISIONING_STARTED", approverId);
            assertProvisioningAuditEvent(connection, requestId, completionEventType, approverId);
            try (var grantStatement = connection.prepareStatement("""
                     select GRANT_ORIGIN, REVOCATION_STATE, REALM_ID, REQUESTER_ID, RESOURCE_TYPE, RESOURCE_ID,
                            RECORDED_TIMESTAMP, EXPIRES_TIMESTAMP
                      from AR_ACCESS_GRANT
                     where REQUEST_ID = ?
                    """)) {
                grantStatement.setString(1, requestId);
                try (ResultSet grant = grantStatement.executeQuery()) {
                    if ("SUCCEEDED".equals(provisioningStatus)) {
                        assertTrue(grant.next(), "Successful provisioning must persist grant provenance.");
                        assertEquals(expectedGrantOrigin, grant.getString("GRANT_ORIGIN"));
                        assertEquals("UNVERIFIED", grant.getString("REVOCATION_STATE"));
                        assertTrue(grant.getString("REALM_ID") != null);
                        assertTrue(grant.getString("REQUESTER_ID") != null);
                        assertTrue(grant.getString("RESOURCE_TYPE") != null);
                        assertTrue(grant.getString("RESOURCE_ID") != null);
                        Long expectedExpiry = "CREATED_BY_EXTENSION".equals(expectedGrantOrigin)
                                && !permanent && requestedDurationSeconds != null
                                ? Instant.ofEpochMilli(grant.getLong("RECORDED_TIMESTAMP"))
                                        .plusSeconds(requestedDurationSeconds).toEpochMilli()
                                : null;
                        assertEquals(expectedExpiry, grant.getObject("EXPIRES_TIMESTAMP", Long.class));
                        assertFalse(grant.next(), "A request must have exactly one grant record.");
                    } else {
                        assertFalse(grant.next(), "Failed provisioning must not persist a grant record.");
                    }
                }
            }
        }
    }

    private void assertProvisioningAuditEvent(
            Connection connection,
            String requestId,
            String eventType,
            String approverId) throws SQLException {
        try (var eventStatement = connection.prepareStatement("""
                select EVENT_TYPE, ACTOR_ID
                  from AR_ACCESS_REQUEST_HISTORY
                 where REQUEST_ID = ?
                   and EVENT_TYPE = ?
                """)) {
            eventStatement.setString(1, requestId);
            eventStatement.setString(2, eventType);
            try (ResultSet result = eventStatement.executeQuery()) {
                assertTrue(result.next(), "Provisioning must persist its audit event.");
                assertEquals(eventType, result.getString("EVENT_TYPE"));
                assertEquals(approverId, result.getString("ACTOR_ID"));
                assertFalse(result.next(), "Exactly one provisioning audit event must be persisted.");
            }
        }
    }

    private void assertEntitlementAuditEvents(String entitlementId, String actorId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     select EVENT_TYPE, ACTOR_ID, REQUESTABLE, VERSION, DISPLAY_NAME
                       from AR_ENTITLEMENT_HISTORY
                      where ENTITLEMENT_ID = ?
                      order by VERSION asc
                     """)) {
            statement.setString(1, entitlementId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "Creating an entitlement must be audited.");
                assertEquals("ENTITLEMENT_CREATED", result.getString("EVENT_TYPE"));
                assertEquals(actorId, result.getString("ACTOR_ID"));
                assertFalse(result.getBoolean("REQUESTABLE"));
                assertEquals(0, result.getLong("VERSION"));
                assertEquals("Catalog Finance Reader", result.getString("DISPLAY_NAME"));

                assertTrue(result.next(), "Updating an entitlement must be audited.");
                assertEquals("ENTITLEMENT_UPDATED", result.getString("EVENT_TYPE"));
                assertEquals(actorId, result.getString("ACTOR_ID"));
                assertTrue(result.getBoolean("REQUESTABLE"));
                assertEquals(1, result.getLong("VERSION"));
                assertEquals("Catalog Finance Reader", result.getString("DISPLAY_NAME"));
                assertFalse(result.next(), "Exactly one audit event per catalog mutation is expected.");
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

    private void assertKeycloakReferenceIsListed(
            GenericContainer<?> server, String accessToken, String type, String search, String expectedId) throws Exception {
        URI endpoint = URI.create("http://%s:%d/realms/master/access-requests/admin/references?type=%s&search=%s"
                .formatted(server.getHost(), server.getMappedPort(8080), type, search));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"id\":\"" + expectedId + "\""));
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

    private void createRealm(GenericContainer<?> server, String adminToken, String realmName) throws Exception {
        URI realmsEndpoint = URI.create("http://%s:%d/admin/realms"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(realmsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"realm":"%s","enabled":true}
                                """.formatted(realmName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, response.statusCode());
    }

    private void assignRealmManagementRoles(
            GenericContainer<?> server,
            String adminToken,
            String userId,
            String... roleNames) throws Exception {
        String realmManagementClientId = clientInternalId(server, adminToken, "master-realm");
        StringBuilder mappings = new StringBuilder("[");
        for (int index = 0; index < roleNames.length; index++) {
            String roleName = roleNames[index];
            URI roleEndpoint = URI.create("http://%s:%d/admin/realms/master/clients/%s/roles/%s"
                    .formatted(server.getHost(), server.getMappedPort(8080), realmManagementClientId, roleName));
            HttpResponse<String> roleResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(roleEndpoint)
                            .header("Authorization", "Bearer " + adminToken)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, roleResponse.statusCode());
            var roleIdMatcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(roleResponse.body());
            assertTrue(roleIdMatcher.find(), "The master realm management role must have an identifier.");
            if (index > 0) {
                mappings.append(',');
            }
            mappings.append("{\"id\":\"").append(roleIdMatcher.group(1))
                    .append("\",\"name\":\"").append(roleName).append("\"}");
        }
        mappings.append(']');

        URI mappingsEndpoint = URI.create("http://%s:%d/admin/realms/master/users/%s/role-mappings/clients/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), userId, realmManagementClientId));
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(mappingsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mappings.toString()))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private ClientRole createClientRole(GenericContainer<?> server, String adminToken, String roleName)
            throws Exception {
        String clientId = "entitlement-target-" + UUID.randomUUID();
        createDirectAccessClient(server, adminToken, clientId);
        String clientInternalId = clientInternalId(server, adminToken, clientId);
        URI rolesEndpoint = URI.create("http://%s:%d/admin/realms/master/clients/%s/roles"
                .formatted(server.getHost(), server.getMappedPort(8080), clientInternalId));
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
        assertTrue(roleIdMatcher.find(), "The created client role must have an identifier.");
        return new ClientRole(clientInternalId, roleIdMatcher.group(1));
    }

    private String createGroup(GenericContainer<?> server, String adminToken, String groupName) throws Exception {
        URI groupsEndpoint = URI.create("http://%s:%d/admin/realms/master/groups"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpResponse<Void> createGroupResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(groupsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"%s"}
                                """.formatted(groupName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, createGroupResponse.statusCode());

        URI groupSearchEndpoint = URI.create("%s?search=%s&exact=true".formatted(groupsEndpoint, groupName));
        HttpResponse<String> groupResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(groupSearchEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, groupResponse.statusCode());
        var groupIdMatcher = Pattern.compile(
                        "\\{[^{}]*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^{}]*\\\"name\\\"\\s*:\\s*\\\""
                                + groupName + "\\\"")
                .matcher(groupResponse.body());
        assertTrue(groupIdMatcher.find(), "The created group must have an identifier.");
        return groupIdMatcher.group(1);
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
        return ensureRealmRoleAndAssignToUser(server, adminToken, userId, roleName);
    }

    private String ensureRealmRoleAndAssignToUser(
            GenericContainer<?> server, String adminToken, String userId, String roleName) throws Exception {
        String roleId = findRealmRoleIdOrNull(server, adminToken, roleName);
        if (roleId == null) {
            roleId = createRealmRole(server, adminToken, roleName);
        }

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

    private String findRealmRoleIdOrNull(GenericContainer<?> server, String adminToken, String roleName) throws Exception {
        URI roleEndpoint = URI.create("http://%s:%d/admin/realms/master/roles/%s"
                .formatted(server.getHost(), server.getMappedPort(8080), roleName));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(roleEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        assertEquals(200, response.statusCode());
        var roleIdMatcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response.body());
        assertTrue(roleIdMatcher.find(), "The realm role must have an identifier.");
        return roleIdMatcher.group(1);
    }

    private String createRealmRole(GenericContainer<?> server, String adminToken, String roleName) throws Exception {
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
        return roleIdMatcher.group(1);
    }

    private void assertRealmRoleAssigned(
            GenericContainer<?> server,
            String adminToken,
            String userId,
            String roleId) throws Exception {
        assertTrue(hasRealmRoleAssignment(server, adminToken, userId, roleId),
                "Provisioning must grant the configured realm role to the requester.");
    }

    private void assertBoundedReferenceLookupAndSelectedId(
            GenericContainer<?> server, String accessToken, String selectedRoleId) throws Exception {
        String base = "http://%s:%d/realms/master/access-requests/admin/references?type=REALM_ROLE"
                .formatted(server.getHost(), server.getMappedPort(8080));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> noSearch = client.send(
                HttpRequest.newBuilder(URI.create(base))
                        .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, noSearch.statusCode());
        assertEquals(0, new ObjectMapper().readTree(noSearch.body()).path("items").size());

        HttpResponse<String> selected = client.send(
                HttpRequest.newBuilder(URI.create(base + "&selectedId=" + selectedRoleId))
                        .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, selected.statusCode());
        assertEquals(selectedRoleId, new ObjectMapper().readTree(selected.body()).path("items").get(0).path("id").asText());

        HttpResponse<String> limited = client.send(
                HttpRequest.newBuilder(URI.create(base + "&search=catalog&max=1"))
                        .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, limited.statusCode());
        var firstPage = new ObjectMapper().readTree(limited.body());
        assertEquals(1, firstPage.path("items").size());
        assertTrue(firstPage.path("hasMore").asBoolean());
        assertEquals(1, firstPage.path("nextFirst").asInt());

        HttpResponse<String> next = client.send(
                HttpRequest.newBuilder(URI.create(base + "&search=catalog&max=1&first=1"))
                        .header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, next.statusCode());
        var secondPage = new ObjectMapper().readTree(next.body());
        assertEquals(1, secondPage.path("items").size());
        assertNotEquals(firstPage.path("items").get(0).path("id").asText(),
                secondPage.path("items").get(0).path("id").asText());
    }

    private void assertRealmRoleNotAssigned(
            GenericContainer<?> server,
            String adminToken,
            String userId,
            String roleId) throws Exception {
        assertFalse(hasRealmRoleAssignment(server, adminToken, userId, roleId),
                "A role not granted or rolled back must not appear in the requester's direct mappings.");
    }

    private boolean hasRealmRoleAssignment(
            GenericContainer<?> server,
            String adminToken,
            String userId,
            String roleId) throws Exception {
        URI roleMappingsEndpoint = URI.create("http://%s:%d/admin/realms/master/users/%s/role-mappings/realm"
                .formatted(server.getHost(), server.getMappedPort(8080), userId));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(roleMappingsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body().contains("\"id\":\"" + roleId + "\"");
    }

    private void assertClientRoleAssigned(
            GenericContainer<?> server,
            String adminToken,
            String userId,
            ClientRole clientRole) throws Exception {
        URI roleMappingsEndpoint = URI.create(
                "http://%s:%d/admin/realms/master/users/%s/role-mappings/clients/%s"
                        .formatted(server.getHost(), server.getMappedPort(8080), userId, clientRole.clientId()));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(roleMappingsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"id\":\"" + clientRole.roleId() + "\""),
                "Provisioning must grant the configured client role to the requester.");
    }

    private void assertGroupMembership(
            GenericContainer<?> server,
            String adminToken,
            String userId,
            String groupId) throws Exception {
        URI groupsEndpoint = URI.create("http://%s:%d/admin/realms/master/users/%s/groups"
                .formatted(server.getHost(), server.getMappedPort(8080), userId));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(groupsEndpoint)
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"id\":\"" + groupId + "\""),
                "Provisioning must add the requester to the configured group.");
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
                         DEFAULT_DURATION_SECONDS,
                         MAX_DURATION_SECONDS,
                         ALLOW_PERMANENT,
                         CREATED_TIMESTAMP,
                         UPDATED_TIMESTAMP,
                         VERSION)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            statement.setLong(10, 2_592_000);
            statement.setLong(11, 7_776_000);
            statement.setBoolean(12, false);
            statement.setLong(13, now);
            statement.setLong(14, now);
            statement.setLong(15, 0);
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

    private record ClientRole(String clientId, String roleId) {
    }
}
