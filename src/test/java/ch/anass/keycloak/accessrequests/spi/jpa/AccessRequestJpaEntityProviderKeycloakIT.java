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
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class AccessRequestJpaEntityProviderKeycloakIT {

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
        }

        try (GenericContainer<?> restartedServer = keycloak()) {
            restartedServer.start();
            assertProviderSchemaApplied();
            assertRealmEndpointExposed(restartedServer);
            assertCatalogEndpointRequiresAuthenticationAndListsPublishedEntitlements(restartedServer);
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
            assertEquals(2, providerChangeSetCount(connection));
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

        String entitlementId = UUID.randomUUID().toString();
        insertPublishedEntitlement(entitlementId);

        HttpRequest authenticatedRequest = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + accessToken(server))
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
    }

    private String accessToken(GenericContainer<?> server) throws Exception {
        URI tokenEndpoint = URI.create("http://%s:%d/realms/master/protocol/openid-connect/token"
                .formatted(server.getHost(), server.getMappedPort(8080)));
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=password&client_id=admin-cli&username=admin&password=admin"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        var matcher = Pattern.compile("\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(response.body());
        assertTrue(matcher.find(), "The token response must contain an access token.");
        return matcher.group(1);
    }

    private void insertPublishedEntitlement(String entitlementId) throws SQLException {
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
            statement.setString(3, "CLIENT_ROLE");
            statement.setString(4, "finance-reader-" + entitlementId);
            statement.setString(5, "Finance Reader");
            statement.setString(6, "Read-only access to the Finance Portal.");
            statement.setString(7, "LOW");
            statement.setString(8, "access-request-approver");
            statement.setBoolean(9, true);
            statement.setLong(10, now);
            statement.setLong(11, now);
            statement.setLong(12, 0);
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
