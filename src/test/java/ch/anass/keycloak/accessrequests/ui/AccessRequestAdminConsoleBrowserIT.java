package ch.anass.keycloak.accessrequests.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class AccessRequestAdminConsoleBrowserIT {

    private static final String DEFAULT_KEYCLOAK_VERSION = "26.8.0";
    private static final String DEFAULT_SELENIUM_CHROME_CONTAINER = "selenium/standalone-chrome:4.45.0-20260606";
    private static final String DEFAULT_POSTGRESQL_CONTAINER = "mirror.gcr.io/postgres:18";
    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version", DEFAULT_KEYCLOAK_VERSION);
    private static final String KEYCLOAK_IMAGE = System.getProperty(
            "keycloak.image", "quay.io/keycloak/keycloak:" + KEYCLOAK_VERSION);
    private static final String SELENIUM_CHROME_CONTAINER = System.getProperty(
            "selenium.chrome.container", DEFAULT_SELENIUM_CHROME_CONTAINER);
    private static final String POSTGRESQL_CONTAINER = System.getProperty(
            "postgresql.container", DEFAULT_POSTGRESQL_CONTAINER);
    private static final Network NETWORK = Network.newNetwork();
    private static final HttpClient HTTP_CLIENT = insecureHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @Test
    void managesThePackagedCatalogAsADedicatedAdministratorAndSafelyRejectsOtherAdministrators() throws Exception {
        try (KeycloakContainer keycloak = keycloak()) {
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            AdminConsoleFixture fixture = configureAdminConsole(keycloak,
                    !AccessRequestBrowserScreenshots.enabled());
            if (AccessRequestBrowserScreenshots.enabled()) {
                captureEmptyAdminCatalog(keycloak, fixture);
                createSeedPackage(keycloak, fixture.globalAdminToken(),
                        createRealmRole(keycloak, fixture.globalAdminToken(), "reporting-readers-late"),
                        fixture.approverRoleId());
            }

            verifyCatalogManagement(keycloak, fixture, false, true);
            verifyCatalogManagement(keycloak, fixture, true, false);
            verifyApprovalAssurancePolicy(keycloak, false);
            verifyApprovalAssurancePolicy(keycloak, true);
            verifyCatalogAccessDenied(keycloak, fixture);
        }
    }

    @Test
    void completesAccessRequestWorkflowAcrossBothConsolesOnPostgres() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(POSTGRESQL_CONTAINER).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("keycloak")
                .withUsername("keycloak")
                .withPassword("keycloak")
                .withNetwork(NETWORK)
                .withNetworkAliases("postgres");
             KeycloakContainer keycloak = keycloakWithPostgres();
             GenericContainer<?> chrome = chrome()) {
            postgres.start();
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            AdminConsoleFixture manager = configureAdminConsole(keycloak);
            String adminToken = manager.globalAdminToken();
            String requestClientId = createRequestTestClient(keycloak, adminToken);
            enableAccountConsoleForAccessRequests(keycloak, adminToken);

            String roleName = "project-reader";
            String entitlementName = "Project reporting access";
            String approverRoleName = "project-approver";
            String roleId = createRealmRole(keycloak, adminToken, roleName);
            String approverRoleId = createRealmRole(keycloak, adminToken, approverRoleName);
            String requesterUsername = "workflow-requester";
            String approverUsername = "workflow-approver";
            String requesterPassword = "browser-requester-password";
            String approverPassword = "browser-approver-password";
            String requesterId = createEnabledUser(keycloak, adminToken, requesterUsername, requesterPassword,
                    "Jane", "Requester");
            String approverId = createEnabledUser(keycloak, adminToken, approverUsername, approverPassword,
                    "Alex", "Approver");
            assignRealmRole(keycloak, adminToken, approverId, approverRoleId, approverRoleName);
            assertRoleNotGranted(keycloak, adminToken, requesterId, roleId);

            AdminConsoleFixture workflowCatalog = new AdminConsoleFixture(
                    adminToken, manager.managerUsername(), manager.managerPassword(),
                    manager.observerUsername(), manager.observerPassword(), roleId, roleName,
                    approverRoleId, approverRoleName);
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, "admin", "admin");
                openAccessRequests(driver);
                createAndPublishAccessPackage(driver, workflowCatalog, entitlementName, true);
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
            String entitlementId = entitlementId(keycloak, adminToken, entitlementName);
            String justification = "Need read-only access for the project.";
            driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAccountConsole(keycloak, driver, requesterUsername, requesterPassword, "request-access");
                By entitlement = By.id("requestable-entitlement-" + entitlementId);
                WebElement row = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(entitlement));
                AccessRequestBrowserScreenshots.capture(driver, "workflow-request-access");
                AccessRequestBrowserScreenshots.capture(driver, "account-catalog-filled-light");
                row.findElement(By.xpath(".//button[normalize-space()='Request access']")).click();
                driver.findElement(By.id("access-request-justification")).sendKeys(justification);
                driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Submit request']")).click();
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//*[@id='requestable-entitlement-" + entitlementId
                                + "']//*[normalize-space()='Request pending']")));
                driver.navigate().to(accountConsoleUri() + "my-requests");
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//strong[normalize-space()=" + xpathLiteral(entitlementName) + "]")));
                AccessRequestBrowserScreenshots.capture(driver, "workflow-my-requests-pending");
                AccessRequestBrowserScreenshots.capture(driver, "account-my-requests-filled-light");
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
            String requesterToken = accessToken(keycloak, requestClientId, requesterUsername, requesterPassword);
            String requestId = onlyRequestId(keycloak, requesterToken, entitlementId);
            assertRoleNotGranted(keycloak, adminToken, requesterId, roleId);

            driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAccountConsole(keycloak, driver, approverUsername, approverPassword, "approvals");
                WebElement row = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.id("pending-request-" + requestId)));
                assertTrue(row.getText().contains(justification));
                assertTrue(row.getText().contains("Jane Requester"));
                assertFalse(row.getText().contains(requesterId));
                AccessRequestBrowserScreenshots.capture(driver, "workflow-approvals-pending");
                AccessRequestBrowserScreenshots.capture(driver, "account-approvals-filled-light");
                row.findElement(By.xpath(".//button[normalize-space()='Approve']")).click();
                driver.findElement(By.id("access-request-decision-comment"))
                        .sendKeys("Approved for the project.");
                driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Confirm approval']"))
                        .click();
                waitFor(driver).until(ExpectedConditions.invisibilityOfElementLocated(
                        By.id("pending-request-" + requestId)));
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }

            assertRoleGranted(keycloak, adminToken, requesterId, roleId);
            String freshRequesterToken = accessToken(keycloak, requestClientId, requesterUsername, requesterPassword);
            JsonNode token = JSON.readTree(Base64.getUrlDecoder().decode(freshRequesterToken.split("\\.")[1]));
            assertTrue(token.path("realm_access").path("roles").isArray());
            assertTrue(token.path("realm_access").path("roles").valueStream()
                    .anyMatch(role -> roleName.equals(role.asText())),
                    "A fresh requester access token must contain the granted target role.");
            assertCompleteRequestHistory(keycloak, adminToken, freshRequesterToken,
                    requestId, requesterId, approverId, justification);
            assertCompletedRequestPersistedInPostgres(postgres, requestId);

            driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAccountConsole(keycloak, driver, requesterUsername, requesterPassword, "my-requests");
                WebElement row = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.id("access-request-" + requestId)));
                assertTrue(row.getText().contains(entitlementName));
                assertTrue(row.getText().contains("Approved"));
                row.findElement(By.xpath(".//button[normalize-space()='View details']")).click();
                WebElement details = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.cssSelector("[role='dialog']")));
                waitFor(driver).until(d -> details.getText().contains("Alex Approver"));
                assertFalse(details.getText().contains(approverId));
                AccessRequestBrowserScreenshots.capture(driver, "workflow-my-requests-approved");
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }

            driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, manager.managerUsername(), manager.managerPassword());
                driver.navigate().to(adminConsoleUri() + "#/master/access-requests/events");
                waitFor(driver).until(ExpectedConditions.elementToBeClickable(By.xpath(
                        "//a[contains(@href, '/access-requests/requests/" + requestId + "')]"))).click();
                waitFor(driver).until(ExpectedConditions.urlContains(
                        "/master/access-requests/requests/" + requestId));
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//h3[normalize-space()='History']")));
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[role='dialog']")));
                assertPageHeading(driver, "Events");
                assertTrue(driver.findElement(By.xpath("//*[@role='tab' and normalize-space()='Events']"))
                        .getAttribute("aria-selected").equals("true"));
                assertAuditDetailValue(driver, "Requester", "Jane Requester");
                assertAuditDetailValue(driver, "Decision status", "Approved");
                assertAuditDetailValue(driver, "Provisioning status", "Succeeded");
                assertAuditHistoryActor(driver, "Requested", "Jane Requester");
                assertAuditHistoryActor(driver, "Approved", "Alex Approver");
                assertAuditHistoryActor(driver, "Provisioning started", "Alex Approver");
                assertAuditHistoryActor(driver, "Provisioning succeeded", "Alex Approver");
                AccessRequestBrowserScreenshots.capture(driver, "workflow-admin-request-history");
                driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Close']")).click();
                waitFor(driver).until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("[role='dialog']")));
                waitFor(driver).until(ExpectedConditions.urlContains("/master/access-requests/events"));
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                        "//a[contains(@href, '/access-requests/requests/" + requestId + "')]")));
                // The browser journey can outlast the admin-cli access token on slower Keycloak versions.
                String galleryAdminToken = accessToken(keycloak, "admin-cli", "admin", "admin");
                String openRequesterId = createEnabledUser(keycloak, galleryAdminToken,
                        "gallery-open-requester", "gallery-password", "Robin", "Requester");
                String closedRequesterId = createEnabledUser(keycloak, galleryAdminToken,
                        "gallery-closed-requester", "gallery-password", "Sam", "Requester");
                AccessRequestBrowserGalleryFixture.Incidents incidents =
                        AccessRequestBrowserGalleryFixture.seed(postgres, requestId,
                                openRequesterId, closedRequesterId, approverId, manager.approverRoleId());
                verifyPopulatedAdminPages(keycloak, driver, galleryAdminToken, incidents,
                        requestId, requesterId);
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
        }
    }

    @Test
    void autoApprovesALowRiskPackageConfiguredInAdminConsoleAndGrantsItFromAccountConsole() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(POSTGRESQL_CONTAINER).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("keycloak")
                .withUsername("keycloak")
                .withPassword("keycloak")
                .withNetwork(NETWORK)
                .withNetworkAliases("postgres");
             KeycloakContainer keycloak = keycloakWithPostgres();
             GenericContainer<?> chrome = chrome()) {
            postgres.start();
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            AdminConsoleFixture manager = configureAdminConsole(keycloak, false);
            String adminToken = manager.globalAdminToken();
            String requestClientId = createRequestTestClient(keycloak, adminToken);
            enableAccountConsoleForAccessRequests(keycloak, adminToken);
            String requesterUsername = "auto-approved-requester";
            String requesterPassword = "browser-requester-password";
            String requesterId = createEnabledUser(keycloak, adminToken,
                    requesterUsername, requesterPassword, "Jordan", "Requester");
            String packageName = "Temporary reporting access";
            assertRoleNotGranted(keycloak, adminToken, requesterId, manager.managedTargetRoleId());

            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, "admin", "admin");
                openAccessRequests(driver);
                createAndPublishAccessPackage(driver, manager, packageName, false, true);
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
            String entitlementId = entitlementId(keycloak, adminToken, packageName);
            HttpResponse<String> catalog = HTTP_CLIENT.send(
                    adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements/" + entitlementId,
                            adminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode(), catalog.body());
            assertTrue(JSON.readTree(catalog.body()).path("autoApprove").asBoolean(),
                    "The Admin Console must persist the automatic approval policy.");

            String justification = "Need temporary access to reporting.";
            driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAccountConsole(keycloak, driver, requesterUsername, requesterPassword, "request-access");
                WebElement row = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.id("requestable-entitlement-" + entitlementId)));
                row.findElement(By.xpath(".//button[normalize-space()='Request access']")).click();
                driver.findElement(By.id("access-request-justification")).sendKeys(justification);
                driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Submit request']")).click();
                driver.navigate().to(accountConsoleUri() + "my-requests");
                WebElement requestRow = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//li[contains(@id, 'access-request-') and .//strong[normalize-space()="
                                + xpathLiteral(packageName) + "]]")));
                waitFor(driver).until(ignored -> requestRow.getText().contains("Approved")
                        && requestRow.getText().contains("Succeeded"));
                AccessRequestBrowserScreenshots.capture(driver, "workflow-my-requests-auto-approved");
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }

            String requesterToken = accessToken(keycloak, requestClientId, requesterUsername, requesterPassword);
            HttpResponse<String> mine = HTTP_CLIENT.send(
                    adminRequest(keycloak, "/realms/master/access-requests/mine", requesterToken)
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, mine.statusCode(), mine.body());
            JsonNode requests = JSON.readTree(mine.body());
            assertEquals(1, requests.path("total").asInt());
            JsonNode request = requests.path("items").get(0);
            String requestId = request.path("id").asText();
            assertEquals(entitlementId, request.path("entitlementId").asText());
            assertEquals("APPROVED", request.path("decisionStatus").asText());
            assertEquals("SUCCEEDED", request.path("provisioningStatus").asText());
            assertRoleGranted(keycloak, adminToken, requesterId, manager.managedTargetRoleId());
            JsonNode token = JSON.readTree(Base64.getUrlDecoder().decode(requesterToken.split("\\.")[1]));
            assertTrue(token.path("realm_access").path("roles").valueStream()
                    .anyMatch(role -> manager.managedTargetRoleName().equals(role.asText())),
                    "A fresh access token must contain the role granted by the access package.");

            HttpResponse<String> audit = HTTP_CLIENT.send(
                    adminRequest(keycloak, "/realms/master/access-requests/admin/requests/" + requestId,
                            adminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, audit.statusCode(), audit.body());
            JsonNode details = JSON.readTree(audit.body());
            assertEquals(justification, details.path("justification").asText());
            assertEquals("CREATED_BY_EXTENSION", details.path("grant").path("origin").asText());
            assertEquals("AUTHORIZED", details.path("grant").path("revocationState").asText());
            assertTrue(details.path("grant").path("expiresAt").isTextual(),
                    "The automatically provisioned temporary grant must have an expiry.");
            JsonNode history = details.path("history");
            assertEquals(4, history.size());
            assertEquals("REQUEST_CREATED", history.get(0).path("type").asText());
            assertEquals(requesterId, history.get(0).path("actorId").asText());
            assertEquals("REQUEST_APPROVED", history.get(1).path("type").asText());
            assertEquals("PROVISIONING_STARTED", history.get(2).path("type").asText());
            assertEquals("PROVISIONING_SUCCEEDED", history.get(3).path("type").asText());
            for (int index = 1; index < history.size(); index++) {
                assertEquals("system:auto-approval", history.get(index).path("actorId").asText());
            }
            assertCompletedRequestPersistedInPostgres(postgres, requestId);
        }
    }

    private void enableAccountConsoleForAccessRequests(KeycloakContainer keycloak, String adminToken) throws Exception {
        HttpResponse<Void> theme = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master", adminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"accountTheme\":\"access-requests\",\"adminTheme\":\"access-requests\"}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, theme.statusCode());

        String accountClientId = findId(
                keycloak, "/admin/realms/master/clients?clientId=account-console", adminToken);
        HttpResponse<Void> mapper = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/clients/" + accountClientId
                        + "/protocol-mappers/models", adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"access-requests-api-audience","protocol":"openid-connect",
                                 "protocolMapper":"oidc-audience-mapper","config":{
                                   "included.client.audience":"access-requests-api",
                                   "access.token.claim":"true","id.token.claim":"false"}}
                                """))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, mapper.statusCode());
    }

    private void logInToAccountConsole(
            KeycloakContainer keycloak, WebDriver driver, String username, String password, String route) {
        driver.navigate().to(accountConsoleUri() + route);
        try {
            waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.id("username"))).sendKeys(username);
        } catch (TimeoutException exception) {
            throw new AssertionError("The Account Console login form was not rendered. Page: %s. Keycloak log: %s"
                    .formatted(driver.findElement(By.tagName("body")).getText(), tail(keycloak.getLogs())), exception);
        }
        driver.findElement(By.id("password")).sendKeys(password);
        driver.findElement(By.id("kc-login")).click();
        waitFor(driver).until(ExpectedConditions.urlContains("/realms/master/account/" + route));
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#app")));
    }

    private String entitlementId(KeycloakContainer keycloak, String adminToken, String name) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements?page=0&size=100", adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        for (JsonNode item : JSON.readTree(response.body()).path("items")) {
            if (name.equals(item.path("displayName").asText())) {
                assertTrue(item.path("requestable").asBoolean(), "The catalog entry must be published.");
                return item.path("id").asText();
            }
        }
        throw new AssertionError("The published entitlement is missing from the admin catalog.");
    }

    private String onlyRequestId(KeycloakContainer keycloak, String requesterToken, String entitlementId)
            throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/mine", requesterToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode page = JSON.readTree(response.body());
        assertEquals(1, page.path("total").asInt(), "The requester must have exactly one request.");
        JsonNode request = page.path("items").get(0);
        assertEquals(entitlementId, request.path("entitlementId").asText());
        assertEquals("PENDING", request.path("decisionStatus").asText());
        return request.path("id").asText();
    }

    private void assertCompleteRequestHistory(
            KeycloakContainer keycloak, String adminToken, String requesterToken, String requestId,
            String requesterId, String approverId, String justification) throws Exception {
        HttpResponse<String> mine = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/mine/" + requestId, requesterToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, mine.statusCode(), mine.body());
        JsonNode requesterView = JSON.readTree(mine.body());
        assertEquals(justification, requesterView.path("justification").asText());
        assertEquals("APPROVED", requesterView.path("decisionStatus").asText());
        assertEquals("SUCCEEDED", requesterView.path("provisioningStatus").asText());

        HttpResponse<String> audit = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/requests/" + requestId, adminToken)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, audit.statusCode(), audit.body());
        JsonNode history = JSON.readTree(audit.body()).path("history");
        assertEquals(4, history.size(), "The completed request must have a complete audit history.");
        assertEquals("REQUEST_CREATED", history.get(0).path("type").asText());
        assertEquals(requesterId, history.get(0).path("actorId").asText());
        assertEquals("REQUEST_APPROVED", history.get(1).path("type").asText());
        assertEquals("PROVISIONING_STARTED", history.get(2).path("type").asText());
        assertEquals("PROVISIONING_SUCCEEDED", history.get(3).path("type").asText());
        for (int index = 1; index < history.size(); index++) {
            assertEquals(approverId, history.get(index).path("actorId").asText());
        }
    }

    private void assertCompletedRequestPersistedInPostgres(PostgreSQLContainer postgres, String requestId)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement request = connection.prepareStatement("""
                     select DECISION_STATUS, PROVISIONING_STATUS
                       from AR_ACCESS_REQUEST
                      where ID = ?
                     """);
             PreparedStatement events = connection.prepareStatement("""
                     select count(*)
                       from AR_ACCESS_REQUEST_HISTORY
                      where REQUEST_ID = ?
                     """)) {
            request.setString(1, requestId);
            try (ResultSet result = request.executeQuery()) {
                assertTrue(result.next(), "The browser-created request must be stored in PostgreSQL.");
                assertEquals("APPROVED", result.getString("DECISION_STATUS"));
                assertEquals("SUCCEEDED", result.getString("PROVISIONING_STATUS"));
                assertFalse(result.next());
            }

            events.setString(1, requestId);
            try (ResultSet result = events.executeQuery()) {
                assertTrue(result.next());
                assertEquals(4, result.getInt(1), "PostgreSQL must contain the complete request history.");
            }
        }
    }

    @Test
    void browsesRequestAuditEventsInsideThePackagedAccessRequestsAdminPage() throws Exception {
        try (KeycloakContainer keycloak = keycloak()) {
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            AdminConsoleFixture admin = configureAdminConsole(keycloak);
            PendingProvisioningFixture pending = createDeletedRoleFailure(keycloak, admin.globalAdminToken());
            HttpResponse<String> auditResponse = HTTP_CLIENT.send(
                    adminRequest(keycloak, "/realms/master/access-requests/admin/events?requestId="
                            + pending.request().requestId(), admin.globalAdminToken()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, auditResponse.statusCode(), auditResponse.body());
            assertTrue(auditResponse.body().contains(pending.request().requestId()), auditResponse.body());

            try (GenericContainer<?> chrome = chrome()) {
                chrome.start();
                RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
                try {
                    configureDriver(driver);
                    logInToAdminConsole(keycloak, driver, admin.managerUsername(), admin.managerPassword());
                    openAccessRequests(driver);

                    WebElement eventsTab = waitFor(driver).until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//*[@role='tab' and normalize-space()='Events']")));
                    eventsTab.click();
                    assertPageHeading(driver, "Events");
                    By requestLink = By.xpath("//a[contains(@href, '/access-requests/requests/"
                            + pending.request().requestId() + "')]");
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(requestLink));
                    assertTrue(driver.getCurrentUrl().contains("/master/access-requests/events"));

                    driver.findElement(By.xpath("//button[contains(normalize-space(.), 'Filters')]")).click();
                    WebElement requestFilter = driver.findElement(By.id("audit-request-id"));
                    requestFilter.sendKeys(pending.request().requestId());
                    driver.findElement(By.xpath("//form[@id='audit-event-search']//button[@type='submit']")).click();
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(requestLink));
                    assertTrue(driver.findElement(By.tagName("body")).getText().contains("Approved"));
                    assertTrue(driver.findElements(requestLink).size() >= 1,
                            "An audit row must link to the request detail.");
                    driver.findElements(requestLink).getFirst().click();
                    waitFor(driver).until(ExpectedConditions.urlContains(
                            "/master/access-requests/requests/" + pending.request().requestId()));
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                            By.xpath("//h3[normalize-space()='History']")));
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[role='dialog']")));
                    assertAuditDetailValue(driver, "Requester", pending.request().requesterUsername());
                    assertAuditDetailValue(driver, "Entitlement", pending.request().displayName());
                    assertAuditDetailValue(driver, "Decision status", "Approved");
                    assertAuditDetailValue(driver, "Provisioning status", "Failed");
                    assertAuditHistoryActor(driver, "Requested", pending.request().requesterUsername());
                    assertAuditHistoryActor(driver, "Approved", pending.approverUsername());
                    assertAuditHistoryActor(driver, "Provisioning failed", pending.approverUsername());
                    assertApiRequestStatus(driver,
                            "/access-requests/admin/requests/" + pending.request().requestId(), 200);
                    driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Close']")).click();
                    waitFor(driver).until(ExpectedConditions.urlContains("/master/access-requests/events"));
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(requestLink));
                    assertNoJavaScriptErrors(driver);
                } finally {
                    driver.quit();
                }
            }
        }
    }

    private void assertAuditDetailValue(WebDriver driver, String label, String expectedValue) {
        By value = By.xpath("//dt[normalize-space()='" + label + "']/following-sibling::dd[1]");
        waitFor(driver).until(ExpectedConditions.textToBePresentInElementLocated(value, expectedValue));
        assertEquals(expectedValue, driver.findElement(value).getText().trim(), label);
    }

    private void assertAuditHistoryActor(WebDriver driver, String eventType, String actorName) {
        By entries = By.cssSelector("[aria-label='History'] li.pf-v5-c-data-list__item");
        waitFor(driver).until(page -> page.findElements(entries).stream()
                .map(WebElement::getText)
                .anyMatch(text -> text.contains(eventType) && text.contains("Actor: " + actorName)));
    }

    @Test
    void retriesARealFailedGrantAfterTheTechnicalFixtureRestoresTheRole() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(POSTGRESQL_CONTAINER).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("keycloak")
                .withUsername("keycloak")
                .withPassword("keycloak")
                .withNetwork(NETWORK)
                .withNetworkAliases("postgres")) {
            postgres.start();
            AdminConsoleFixture fixture;
            FailedProvisioningFixture failure;
            try (KeycloakContainer firstServer = keycloakWithPostgres()) {
                firstServer.start();
                configureAdminCliTokenBehavior(firstServer);
                fixture = configureAdminConsole(firstServer);
                failure = createDeletedRoleFailure(firstServer, fixture.globalAdminToken()).request();
                assertFailedProvisioningIsVisible(
                        firstServer, fixture.globalAdminToken(), failure, "RESOURCE_TYPE_MISMATCH");
                assertRequesterStateAndHistory(firstServer, failure, "FAILED",
                        "REQUEST_APPROVED", "PROVISIONING_STARTED", "PROVISIONING_FAILED");
                String replacementRoleId = createRealmRole(
                        firstServer, fixture.globalAdminToken(), failure.roleName());
                restoreOriginalRoleIdForTest(postgres, failure, replacementRoleId);
            }

            // Restart to clear Keycloak's role cache after the fixture-only database repair.
            try (KeycloakContainer keycloak = keycloakWithPostgres()) {
                keycloak.start();
                restorePackageRoleMapping(keycloak, accessToken(keycloak, "admin-cli", "admin", "admin"), failure);
                try (GenericContainer<?> chrome = chrome()) {
                    chrome.start();
                    RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
                    try {
                        configureDriver(driver);
                        logInToAdminConsole(keycloak, driver, fixture.managerUsername(), fixture.managerPassword());
                        openAccessRequests(driver);
                        openFailedProvisioning(driver);
                        assertPageHeading(driver, "Provisioning failures");
                        retryFailedProvisioningInBrowser(
                                driver, failure, "The resource no longer matches the entitlement type.");
                        assertApiRequestStatus(
                                driver, "/admin/requests/" + failure.requestId() + "/provisioning/retry", 200);
                        assertNoJavaScriptErrors(driver);
                    } finally {
                        driver.quit();
                    }
                }

                assertProvisioningRecovered(keycloak, failure);
            }
        }
    }

    @Test
    void replacesADeletedRoleWithANewEntitlementAndApprovalWithoutRebindingTheOldRequest() throws Exception {
        try (KeycloakContainer keycloak = keycloak()) {
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            AdminConsoleFixture admin = configureAdminConsole(keycloak);
            PendingProvisioningFixture pending = createDeletedRoleFailure(keycloak, admin.globalAdminToken());
            FailedProvisioningFixture old = pending.request();
            String replacementRoleId = createRealmRole(keycloak, admin.globalAdminToken(), old.roleName());
            assertFalse(replacementRoleId.equals(old.roleId()),
                    "Keycloak must assign a new identity to a role recreated through its Admin API.");
            assertRoleNotGranted(keycloak, admin.globalAdminToken(), old.requesterId(), replacementRoleId);

            try (GenericContainer<?> chrome = chrome()) {
                chrome.start();
                RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
                try {
                    configureDriver(driver);
                    logInToAdminConsole(keycloak, driver, admin.managerUsername(), admin.managerPassword());
                    openAccessRequests(driver);
                    openFailedProvisioning(driver);
                    assertPageHeading(driver, "Provisioning failures");
                    closeFailedProvisioningInBrowser(driver, old);
                    assertApiRequestStatus(driver,
                            "/admin/requests/" + old.requestId() + "/provisioning/close", 200);
                    assertNoJavaScriptErrors(driver);
                } finally {
                    driver.quit();
                }
            }

            String requesterToken = accessToken(keycloak, old.requestClientId(),
                    old.requesterUsername(), old.requesterPassword());
            assertClosedOriginalRequest(keycloak, admin.globalAdminToken(), requesterToken, old);
            assertRoleNotGranted(keycloak, admin.globalAdminToken(), old.requesterId(), replacementRoleId);

            deactivateEntitlement(keycloak, admin.globalAdminToken(), old.entitlementId(), pending);
            String newEntitlementId = createPublishedReplacementPackage(
                    keycloak, admin.globalAdminToken(), replacementRoleId, pending.approverRoleId());
            String newRequestId = submitReplacementRequest(keycloak, requesterToken, newEntitlementId);
            assertFalse(newRequestId.equals(old.requestId()));
            assertRoleNotGranted(keycloak, admin.globalAdminToken(), old.requesterId(), replacementRoleId);

            HttpResponse<String> approved = HTTP_CLIENT.send(
                    adminRequest(keycloak, "/realms/master/access-requests/" + newRequestId + "/approve",
                            pending.approverToken())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"comment\":\"Approved for the replacement role.\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, approved.statusCode(), approved.body());
            assertTrue(approved.body().contains("\"provisioningStatus\":\"SUCCEEDED\""), approved.body());
            assertRoleGranted(keycloak, admin.globalAdminToken(), old.requesterId(), replacementRoleId);
            assertClosedOriginalRequest(keycloak, admin.globalAdminToken(), requesterToken, old);
        }
    }

    private KeycloakContainer keycloak() {
        return new KeycloakContainer(KEYCLOAK_IMAGE)
                .withNetwork(NETWORK)
                .withNetworkAliases("keycloak")
                .withEnv("KC_HOSTNAME", "keycloak")
                .withAdminUsername("admin")
                .withAdminPassword("admin")
                .useTls()
                .withProviderLibsFrom(List.of(providerJar().toFile()))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private KeycloakContainer keycloakWithPostgres() {
        return keycloak()
                .withEnv("KC_DB", "postgres")
                .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/keycloak")
                .withEnv("KC_DB_USERNAME", "keycloak")
                .withEnv("KC_DB_PASSWORD", "keycloak");
    }

    private Path providerJar() {
        Path providerJar = Path.of("target", "keycloak-access-requests.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(providerJar), "The provider JAR must be built before browser integration tests run.");
        return providerJar;
    }

    private void configureAdminCliTokenBehavior(KeycloakContainer keycloak) throws Exception {
        try {
            Method method = KeycloakContainer.class.getMethod(
                    "disableLightweightAccessTokenForAdminCliClient", String.class);
            method.invoke(keycloak, "master");
        } catch (NoSuchMethodException ignored) {
            // Only required by Keycloak 26.7 test containers that enable lightweight admin-cli tokens.
        }
    }

    private AdminConsoleFixture configureAdminConsole(KeycloakContainer keycloak) throws Exception {
        return configureAdminConsole(keycloak, true);
    }

    private AdminConsoleFixture configureAdminConsole(KeycloakContainer keycloak, boolean seedCatalog) throws Exception {
        String globalAdminToken = accessToken(keycloak, "admin-cli", "admin", "admin");
        selectAdminTheme(keycloak, globalAdminToken);

        String managerUsername = "catalog-manager";
        String managerPassword = "catalog-manager-password";
        String observerUsername = "catalog-observer";
        String observerPassword = "catalog-observer-password";
        String managerUserId = createEnabledUser(keycloak, globalAdminToken, managerUsername, managerPassword);
        String observerUserId = createEnabledUser(keycloak, globalAdminToken, observerUsername, observerPassword);

        String managerRoleName = "manage-access-requests";
        String managerRoleId = createRealmRole(keycloak, globalAdminToken, managerRoleName);
        String approverRoleName = "reporting-approvers";
        String managedTargetRoleName = "managed-reporting-readers";
        String approverRoleId = createRealmRole(keycloak, globalAdminToken, approverRoleName);
        String managedTargetRoleId = createRealmRole(keycloak, globalAdminToken, managedTargetRoleName);

        assignRealmRole(keycloak, globalAdminToken, managerUserId, managerRoleId, managerRoleName);
        assignMasterRealmManagementRole(keycloak, globalAdminToken, managerUserId, "view-realm");
        assignMasterRealmManagementRole(keycloak, globalAdminToken, observerUserId, "view-realm");
        if (seedCatalog) {
            String seedTargetRoleId = createRealmRole(keycloak, globalAdminToken, "reporting-readers");
            createSeedPackage(keycloak, globalAdminToken, seedTargetRoleId, approverRoleId);
        }

        return new AdminConsoleFixture(
                globalAdminToken,
                managerUsername,
                managerPassword,
                observerUsername,
                observerPassword,
                managedTargetRoleId,
                managedTargetRoleName,
                approverRoleId,
                approverRoleName);
    }

    private void captureEmptyAdminCatalog(KeycloakContainer keycloak, AdminConsoleFixture fixture) throws Exception {
        try (GenericContainer<?> chrome = chrome()) {
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, "admin", "admin");
                openAccessRequests(driver);
                assertPageHeading(driver, "Catalog");
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                        "//*[normalize-space()='No access entitlements have been configured yet.']")));
                AccessRequestBrowserScreenshots.capture(driver, "admin-catalog-empty-light");
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
        }
    }

    private void verifyCatalogManagement(
            KeycloakContainer keycloak, AdminConsoleFixture fixture, boolean darkMode, boolean exerciseCatalogWorkflow)
            throws Exception {
        try (GenericContainer<?> chrome = chrome()) {
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(darkMode));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, "admin", "admin");
                assertThemeMode(driver, darkMode);
                openAccessRequests(driver);
                assertPageHeading(driver, "Catalog");
                assertCatalogLoaded(driver, "Browser seed package");

                if (exerciseCatalogWorkflow) {
                    String packageName = "Temporary reporting access";
                    createAndPublishAccessPackage(driver, fixture, packageName, false);
                    assertAccessPackageWasPersisted(keycloak, fixture, packageName);
                    updateEntitlement(driver, packageName);
                    assertEntitlementWasPersisted(keycloak, fixture.globalAdminToken(), packageName);
                }

                AccessRequestBrowserScreenshots.capture(driver,
                        darkMode ? "admin-catalog-dark" : "admin-catalog-light");
                if (!darkMode) {
                    driver.navigate().to(adminConsoleUri() + "#/master/access-requests/events");
                    assertPageHeading(driver, "Events");
                    WebElement searchEvents = waitFor(driver).until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//button[contains(normalize-space(.), 'Filters')]")));
                    searchEvents.click();
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.id("audit-event-search")));
                    assertEquals("true", searchEvents.getAttribute("aria-expanded"));
                    AccessRequestBrowserScreenshots.capture(driver, "admin-events-light");
                    driver.findElement(By.id("audit-requester-search")).sendKeys(fixture.managerUsername());
                    WebElement requester = waitFor(driver).until(ExpectedConditions.elementToBeClickable(
                            By.id("audit-requester")));
                    waitFor(driver).until(ignored -> new Select(requester).getOptions().stream()
                            .anyMatch(option -> option.getText().contains(fixture.managerUsername())));
                    new Select(requester).selectByVisibleText(fixture.managerUsername());
                    driver.findElement(By.xpath("//form[@id='audit-event-search']//button[@type='submit']")).click();
                    waitFor(driver).until(ignored -> apiRequests(driver).stream().anyMatch(request -> {
                        String url = (String) request.get("url");
                        return url.contains("/access-requests/admin/events?") && url.contains("requesterId=");
                    }));
                    assertApiRequestStatus(driver, "/access-requests/admin/audit-users", 200);
                    assertApiRequestStatus(driver, "requesterId=", 200);
                }

                openFailedProvisioning(driver);
                assertPageHeading(driver, "Provisioning failures");
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                        "//h2[normalize-space()='There are no failed provisioning requests.']")));
                assertApiRequestStatus(driver, "/access-requests/admin/provisioning-failures", 200);
                if (!darkMode) {
                    AccessRequestBrowserScreenshots.capture(driver, "admin-failed-provisioning-light");
                }
                if (!darkMode) {
                    new Select(driver.findElement(By.id("provisioning-failure-status"))).selectByValue("CLOSED");
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                            "//h2[normalize-space()='There are no closed provisioning failures.']")));
                    AccessRequestBrowserScreenshots.capture(driver, "admin-provisioning-failures-closed-empty-light");
                }

                driver.navigate().to(adminConsoleUri() + "#/master/access-requests/notification-deliveries");
                assertPageHeading(driver, "Email notifications");
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                        "//h2[normalize-space()='There are no failed notification emails.']")));
                assertApiRequestStatus(driver, "/access-requests/admin/notification-deliveries/summary", 200);
                AccessRequestBrowserScreenshots.capture(driver,
                        darkMode ? "admin-email-notifications-dark" : "admin-email-notifications-light");

                driver.navigate().to(adminConsoleUri() + "#/master/access-requests/revocation-failures");
                assertPageHeading(driver, "Revocation failures");
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                        "//h2[normalize-space()='No open revocation failures.']")));
                assertApiRequestStatus(driver, "/access-requests/admin/revocation-failures", 200);
                AccessRequestBrowserScreenshots.capture(driver,
                        darkMode ? "admin-revocation-failures-dark" : "admin-revocation-failures-light");

                assertPackagedAssetsLoaded(driver);
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
        }
    }

    private void verifyApprovalAssurancePolicy(KeycloakContainer keycloak, boolean darkMode) throws Exception {
        try (GenericContainer<?> chrome = chrome()) {
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(darkMode));
            try {
                configureDriver(driver);
                driver.manage().window().setSize(new org.openqa.selenium.Dimension(1440, 1100));
                logInToAdminConsole(keycloak, driver, "admin", "admin");
                assertThemeMode(driver, darkMode);
                driver.navigate().to(adminConsoleUri() + "#/master/access-requests/assurance-policy");
                assertPageHeading(driver, "Approval assurance");
                waitFor(driver).until(ExpectedConditions.attributeToBe(
                        By.id("high-age"), "value", "1800"));
                assertEquals("300", driver.findElement(By.id("critical-age")).getAttribute("value"));
                assertApiRequestStatus(driver, "/access-requests/admin/assurance-policy", 200);
                AccessRequestBrowserScreenshots.capture(driver,
                        darkMode ? "admin-approval-assurance-dark" : "admin-approval-assurance-light");
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
        }
    }

    private void verifyPopulatedAdminPages(KeycloakContainer keycloak, WebDriver driver,
            String adminToken, AccessRequestBrowserGalleryFixture.Incidents incidents,
            String grantedRequestId, String requesterId)
            throws Exception {
        String base = "/realms/master/access-requests/admin/";
        HttpResponse<String> notificationResponse = HTTP_CLIENT.send(
                adminRequest(keycloak, base + "notification-deliveries", adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, notificationResponse.statusCode(), notificationResponse.body());
        assertTrue(notificationResponse.body().contains(incidents.openProvisioningRequestId()));
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1920, 1400));

        driver.navigate().to(adminConsoleUri() + "#/master/access-requests/notification-deliveries");
        assertPageHeading(driver, "Email notifications");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//button[normalize-space()='Retry sending']")));
        AccessRequestBrowserScreenshots.capture(driver, "admin-email-notifications-filled-light");

        driver.navigate().to(adminConsoleUri() + "#/master/access-requests/provisioning-failures");
        assertPageHeading(driver, "Provisioning failures");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                By.id("failed-provisioning-" + incidents.openProvisioningRequestId())));
        AccessRequestBrowserScreenshots.capture(driver, "admin-provisioning-failures-filled-light");
        new Select(driver.findElement(By.id("provisioning-failure-status"))).selectByValue("CLOSED");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                By.id("failed-provisioning-" + incidents.closedProvisioningRequestId())));
        AccessRequestBrowserScreenshots.capture(driver, "admin-provisioning-failures-closed-light");

        driver.navigate().to(adminConsoleUri() + "#/master/access-requests/revocation-failures");
        assertPageHeading(driver, "Revocation failures");
        HttpResponse<String> revocationResponse = HTTP_CLIENT.send(
                adminRequest(keycloak, base + "revocation-failures?state=OPEN", adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, revocationResponse.statusCode(), revocationResponse.body());
        assertTrue(revocationResponse.body().contains(grantedRequestId), revocationResponse.body());
        try {
            waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                    By.id("revocation-failure-" + grantedRequestId)));
        } catch (TimeoutException exception) {
            throw new AssertionError("The revocation API returned the incident, but the page did not show it. "
                    + "Page: " + driver.findElement(By.tagName("body")).getText()
                    + ". API: " + revocationResponse.body(), exception);
        }
        AccessRequestBrowserScreenshots.capture(driver, "admin-revocation-failures-filled-light");
        new Select(driver.findElement(By.id("revocation-failure-status"))).selectByValue("RESOLVED");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//h2[normalize-space()='No resolved revocation failures.']")));
        AccessRequestBrowserScreenshots.capture(driver, "admin-revocation-failures-resolved-empty-light");

        // Resolve the incident only after Keycloak confirms that the package membership was removed.
        HttpResponse<Void> membershipRemoved = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users/" + requesterId
                        + "/groups/" + incidents.deliveryGroupId(), adminToken)
                        .DELETE().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(204, membershipRemoved.statusCode());
        new Select(driver.findElement(By.id("revocation-failure-status"))).selectByValue("OPEN");
        // PatternFly DataListAction does not render its required id prop; locate the row button instead.
        waitFor(driver).until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//*[@id='revocation-failure-" + grantedRequestId
                        + "']/ancestor::li//button[normalize-space()='Confirm removal']"))).click();
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.id("revocation-resolution-reason")))
                .sendKeys("Package membership removed by an administrator.");
        driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Confirm removal']")).click();
        waitFor(driver).until(ExpectedConditions.invisibilityOfElementLocated(
                By.id("revocation-failure-" + grantedRequestId)));
        assertApiRequestStatus(driver, "/access-requests/admin/grants/" + grantedRequestId
                + "/revocation/resolve", 200);
        new Select(driver.findElement(By.id("revocation-failure-status"))).selectByValue("RESOLVED");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                By.id("revocation-failure-" + grantedRequestId)));
        AccessRequestBrowserScreenshots.capture(driver, "admin-revocation-failures-resolved-filled-light");

        driver.navigate().to(adminConsoleUri() + "#/master/access-requests/events");
        assertPageHeading(driver, "Events");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//table[@aria-label='Events']//td[normalize-space()='Provisioning failed']")));
        AccessRequestBrowserScreenshots.capture(driver, "admin-events-filled-light");
    }

    private void verifyCatalogAccessDenied(KeycloakContainer keycloak, AdminConsoleFixture fixture) throws Exception {
        try (GenericContainer<?> chrome = chrome()) {
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, fixture.observerUsername(), fixture.observerPassword());
                openAccessRequestsDirectly(driver);
                assertPageHeading(driver, "Catalog");
                assertPermissionDenied(driver);
                assertTrue(driver.findElements(By.xpath("//button[normalize-space()='Create access package']")).isEmpty(),
                        "An administrator without manage-access-requests must not see catalog write controls.");
                assertApiRequestStatus(driver, "/access-requests/admin/capabilities", 403);
                driver.navigate().to(adminConsoleUri() + "#/master/access-requests/provisioning-failures");
                assertPageHeading(driver, "Provisioning failures");
                assertPermissionDenied(driver);
                assertTrue(driver.findElements(By.xpath("//button[normalize-space()='Retry provisioning']")).isEmpty());
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
        }
    }

    private GenericContainer<?> chrome() {
        return new GenericContainer<>(DockerImageName.parse(SELENIUM_CHROME_CONTAINER))
                .withNetwork(NETWORK)
                .withExposedPorts(4444)
                .waitingFor(Wait.forHttp("/wd/hub/status").forPort(4444).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    private ChromeOptions chromeOptions(boolean darkMode) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments(
                "--headless=new", "--window-size=1440,1000", "--disable-dev-shm-usage", "--ignore-certificate-errors");
        if (darkMode) {
            options.addArguments("--force-dark-mode");
        }

        LoggingPreferences loggingPreferences = new LoggingPreferences();
        loggingPreferences.enable(LogType.BROWSER, Level.ALL);
        options.setCapability("goog:loggingPrefs", loggingPreferences);
        return options;
    }

    private URI webDriverUri(GenericContainer<?> chrome) {
        return URI.create("http://%s:%d/wd/hub".formatted(chrome.getHost(), chrome.getMappedPort(4444)));
    }

    private void configureDriver(RemoteWebDriver driver) {
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
    }

    private void logInToAdminConsole(
            KeycloakContainer keycloak, WebDriver driver, String username, String password) {
        driver.navigate().to(adminConsoleUri());
        WebDriverWait wait = waitFor(driver);
        try {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("username"))).sendKeys(username);
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    "The Keycloak admin login form was not rendered. URL: %s. Page: %s. Browser log: %s. Keycloak log: %s"
                            .formatted(driver.getCurrentUrl(), driver.findElement(By.tagName("body")).getText(),
                                    browserLog(driver), tail(keycloak.getLogs())),
                    exception);
        }
        driver.findElement(By.id("password")).sendKeys(password);
        driver.findElement(By.id("kc-login")).click();
        wait.until(ExpectedConditions.urlContains("/admin/master/console/"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#app")));
    }

    private void assertThemeMode(WebDriver driver, boolean darkMode) {
        JavascriptExecutor javascript = (JavascriptExecutor) driver;
        boolean browserPrefersDarkMode = (Boolean) javascript.executeScript(
                "return window.matchMedia('(prefers-color-scheme: dark)').matches;");
        boolean themeSupportsDarkMode = (Boolean) javascript.executeScript(
                "return JSON.parse(document.getElementById('environment').textContent).darkMode;");
        boolean darkModeClassApplied = (Boolean) javascript.executeScript(
                "return document.documentElement.classList.contains('pf-v5-theme-dark');");

        assertTrue(themeSupportsDarkMode, "The selected admin theme must retain Keycloak dark-mode support.");
        assertEquals(darkMode, browserPrefersDarkMode, "Chrome must emulate the requested color scheme.");
        assertEquals(darkMode, darkModeClassApplied, "Keycloak must apply its dark-mode class to the custom theme.");
    }

    private void openAccessRequests(WebDriver driver) {
        By accessRequests = By.xpath("//a[normalize-space()='Access requests']");
        try {
            WebElement link = waitFor(driver).until(ExpectedConditions.elementToBeClickable(accessRequests));
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center'});", link);
            link.click();
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    "The Administration Console navigation did not expose Access requests. Page: %s. Source: %s"
                            .formatted(driver.findElement(By.tagName("body")).getText(), tail(driver.getPageSource())),
                    exception);
        }
    }

    private void openAccessRequestsDirectly(WebDriver driver) {
        driver.navigate().to(adminConsoleUri() + "#/master/access-requests");
    }

    private void openFailedProvisioning(WebDriver driver) {
        By failedProvisioning = By.xpath("//*[@role='tab' and normalize-space()='Provisioning failures']");
        WebElement link = waitFor(driver).until(ExpectedConditions.elementToBeClickable(failedProvisioning));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center'});", link);
        link.click();
    }

    private void retryFailedProvisioningInBrowser(
            WebDriver driver, FailedProvisioningFixture failure, String expectedCause) {
        WebDriverWait wait = waitFor(driver);
        String itemXPath = "//*[contains(@class, 'pf-v5-c-data-list__item') and .//h3[normalize-space()="
                + xpathLiteral(failure.displayName()) + "]]";
        WebElement item = wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(itemXPath)));
        assertTrue(item.getText().contains(failure.requestId()));
        assertTrue(item.getText().contains(failure.requesterUsername()));
        assertTrue(item.getText().contains(expectedCause));
        assertFalse(item.getText().contains("The configured Keycloak role no longer exists."));
        item.findElement(By.xpath(".//button[normalize-space()='Retry provisioning']")).click();

        WebElement dialog = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[role='dialog']")));
        assertTrue(dialog.getText().contains(failure.requestId()),
                "The retry dialog must identify the failed request.");
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//*[@role='dialog']//button[normalize-space()='Retry provisioning']"))).click();

        try {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                    "//*[contains(@class, 'pf-v5-c-alert__title') and "
                            + "contains(normalize-space(.), 'Provisioning retry completed successfully.')]")));
        } catch (TimeoutException exception) {
            throw new AssertionError("The retry did not report success. Page: %s. Requests: %s. Browser log: %s"
                    .formatted(driver.findElement(By.tagName("body")).getText(), apiRequests(driver),
                            browserLog(driver)), exception);
        }
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//h2[normalize-space()='There are no failed provisioning requests.']")));
        assertTrue(driver.findElements(By.xpath(itemXPath)).isEmpty(),
                "The recovered request must disappear from the failed provisioning list.");
    }

    private void closeFailedProvisioningInBrowser(WebDriver driver, FailedProvisioningFixture failure) {
        WebDriverWait wait = waitFor(driver);
        String itemXPath = "//*[contains(@class, 'pf-v5-c-data-list__item') and .//h3[normalize-space()="
                + xpathLiteral(failure.displayName()) + "]]";
        WebElement item = wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(itemXPath)));
        assertTrue(item.getText().contains(failure.requestId()));
        assertTrue(item.getText().contains("The resource no longer matches the entitlement type."));
        item.findElement(By.xpath(".//button[normalize-space()='Close failure']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("provisioning-closure-reason")))
                .sendKeys("The original role was deleted; a newly approved request targets its replacement.");
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//*[@role='dialog']//button[normalize-space()='Close failure']"))).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//*[contains(@class, 'pf-v5-c-alert__title') and "
                        + "contains(normalize-space(.), 'The failure was closed without granting access.')]")));
        new Select(wait.until(ExpectedConditions.elementToBeClickable(
                By.id("provisioning-failure-status")))).selectByValue("CLOSED");
        WebElement archived = wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(itemXPath)));
        assertTrue(archived.getText().contains(failure.requestId()));
        assertTrue(archived.getText().contains("The original role was deleted"));
        assertTrue(archived.findElements(By.xpath(".//button[normalize-space()='Retry provisioning']")).isEmpty());
    }

    private PendingProvisioningFixture createDeletedRoleFailure(
            KeycloakContainer keycloak, String globalAdminToken) throws Exception {
        PendingProvisioningFixture pending = createPendingProvisioningRequest(keycloak, globalAdminToken);
        FailedProvisioningFixture failure = pending.request();
        HttpResponse<Void> deletedRole = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/roles/" + failure.roleName(), globalAdminToken)
                        .DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, deletedRole.statusCode());
        HttpResponse<String> approved = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/" + failure.requestId() + "/approve",
                        pending.approverToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"comment\":\"Approved for recovery.\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approved.statusCode(), approved.body());
        assertTrue(approved.body().contains("\"provisioningStatus\":\"FAILED\""), approved.body());
        return pending;
    }

    private PendingProvisioningFixture createPendingProvisioningRequest(
            KeycloakContainer keycloak, String globalAdminToken) throws Exception {
        String requesterUsername = "retry-requester-" + UUID.randomUUID();
        String approverUsername = "retry-approver-" + UUID.randomUUID();
        String requesterPassword = "retry-requester-password";
        String approverPassword = "retry-approver-password";
        String requesterId = createEnabledUser(keycloak, globalAdminToken, requesterUsername, requesterPassword);
        String approverId = createEnabledUser(keycloak, globalAdminToken, approverUsername, approverPassword);
        String approverRoleName = "retry-approver-role-" + UUID.randomUUID();
        String approverRoleId = createRealmRole(keycloak, globalAdminToken, approverRoleName);
        assignRealmRole(keycloak, globalAdminToken, approverId, approverRoleId, approverRoleName);
        String targetRoleName = "retry-target-role-" + UUID.randomUUID();
        String targetRoleId = createRealmRole(keycloak, globalAdminToken, targetRoleName);
        String displayName = "Recoverable browser entitlement";

        HttpResponse<String> createdEntitlement = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/access-packages", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"displayName":"%s",
                                 "description":"Verifies browser-driven provisioning recovery.","riskLevel":"LOW",
                                 "approverRoleId":"%s",
                                 "roleMappings":[{"type":"REALM_ROLE","roleId":"%s"}]}
                                """.formatted(displayName, approverRoleId, targetRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, createdEntitlement.statusCode(), createdEntitlement.body());
        String entitlementId = responseId(createdEntitlement.body());
        String groupId = JSON.readTree(createdEntitlement.body()).path("resourceId").asText();
        HttpResponse<String> activated = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements/" + entitlementId,
                        globalAdminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"displayName":"%s","description":"Verifies browser-driven provisioning recovery.",
                                 "riskLevel":"LOW","approverRoleId":"%s","requestable":true,"version":0}
                                """.formatted(displayName, approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, activated.statusCode(), activated.body());

        String requestClientId = createRequestTestClient(keycloak, globalAdminToken);
        String requesterToken = accessToken(keycloak, requestClientId, requesterUsername, requesterPassword);
        String approverToken = accessToken(keycloak, requestClientId, approverUsername, approverPassword);
        HttpResponse<String> submitted = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/requests", requesterToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"entitlementId":"%s","justification":"I need this role for the recovery test."}
                                """.formatted(entitlementId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, submitted.statusCode(), submitted.body());
        String requestId = responseId(submitted.body());
        return new PendingProvisioningFixture(
                new FailedProvisioningFixture(requestId, entitlementId, requesterId,
                        requestClientId, requesterUsername, requesterPassword,
                        targetRoleId, targetRoleName, groupId, displayName),
                approverId, approverUsername, approverRoleId, approverToken);
    }

    private void restoreOriginalRoleIdForTest(
            PostgreSQLContainer postgres, FailedProvisioningFixture failure, String replacementRoleId)
            throws Exception {
        // Keycloak's Admin API assigns a new ID on recreation. Restore the original identity only in this test database.
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     update KEYCLOAK_ROLE
                        set ID = ?
                      where ID = ?
                     """)) {
            statement.setString(1, failure.roleId());
            statement.setString(2, replacementRoleId);
            assertEquals(1, statement.executeUpdate(), "The deleted role identity must be restored exactly once.");
        }
    }

    private void restorePackageRoleMapping(
            KeycloakContainer keycloak, String globalAdminToken, FailedProvisioningFixture failure)
            throws Exception {
        HttpResponse<String> restored = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/groups/" + failure.groupId()
                        + "/role-mappings/realm", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                [{"id":"%s","name":"%s"}]
                                """.formatted(failure.roleId(), failure.roleName())))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, restored.statusCode(), restored.body());
    }

    private void assertFailedProvisioningIsVisible(
            KeycloakContainer keycloak, String globalAdminToken,
            FailedProvisioningFixture failure, String failureCode)
            throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/provisioning-failures", globalAdminToken)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"id\":\"" + failure.requestId() + "\""), response.body());
        assertTrue(response.body().contains("\"failureCode\":\"" + failureCode + "\""), response.body());
        HttpResponse<String> mappings = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users/" + failure.requesterId()
                        + "/role-mappings/realm/composite", globalAdminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, mappings.statusCode());
        assertFalse(mappings.body().contains("\"id\":\"" + failure.roleId() + "\""),
                "The failed grant must not assign access before the browser retry.");
    }

    private void assertRequesterStateAndHistory(
            KeycloakContainer keycloak, FailedProvisioningFixture failure,
            String expectedStatus, String... expectedEvents) throws Exception {
        String requesterToken = accessToken(keycloak, failure.requestClientId(),
                failure.requesterUsername(), failure.requesterPassword());
        HttpResponse<String> details = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/mine/" + failure.requestId(),
                        requesterToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, details.statusCode(), details.body());
        assertTrue(details.body().contains("\"provisioningStatus\":\"" + expectedStatus + "\""), details.body());
        for (String event : expectedEvents) {
            assertTrue(details.body().contains("\"type\":\"" + event + "\""), details.body());
        }
    }

    private String createRequestTestClient(KeycloakContainer keycloak, String globalAdminToken) throws Exception {
        HttpResponse<Void> audienceClient = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/clients", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"clientId":"access-requests-api","enabled":true,"protocol":"openid-connect",
                                 "standardFlowEnabled":false,"directAccessGrantsEnabled":false}
                                """))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, audienceClient.statusCode());

        String clientId = "retry-browser-client-" + UUID.randomUUID();
        HttpResponse<Void> client = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/clients", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"clientId":"%s","enabled":true,"publicClient":true,"directAccessGrantsEnabled":true}
                                """.formatted(clientId)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, client.statusCode());
        String internalId = findId(keycloak, "/admin/realms/master/clients?clientId=" + clientId, globalAdminToken);

        HttpResponse<Void> mapper = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/clients/" + internalId
                        + "/protocol-mappers/models", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"retry-test-audience","protocol":"openid-connect",
                                 "protocolMapper":"oidc-audience-mapper","config":{
                                   "included.client.audience":"access-requests-api",
                                   "access.token.claim":"true","id.token.claim":"false"}}
                                """))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, mapper.statusCode());
        return clientId;
    }

    private void assertProvisioningRecovered(KeycloakContainer keycloak, FailedProvisioningFixture failure)
            throws Exception {
        String globalAdminToken = accessToken(keycloak, "admin-cli", "admin", "admin");
        HttpResponse<String> mappings = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users/" + failure.requesterId()
                        + "/role-mappings/realm/composite", globalAdminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, mappings.statusCode());
        assertTrue(mappings.body().contains("\"id\":\"" + failure.roleId() + "\""),
                "The browser retry must grant the restored realm role to the requester.");

        assertRequesterStateAndHistory(keycloak, failure, "SUCCEEDED",
                "PROVISIONING_FAILED", "PROVISIONING_SUCCEEDED");

        HttpResponse<String> remaining = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/provisioning-failures", globalAdminToken)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, remaining.statusCode());
        assertTrue(remaining.body().contains("\"total\":0"), remaining.body());
    }

    private void assertClosedOriginalRequest(
            KeycloakContainer keycloak, String globalAdminToken, String requesterToken,
            FailedProvisioningFixture failure) throws Exception {
        String requestPath = "/realms/master/access-requests/admin/requests/" + failure.requestId();
        HttpResponse<String> retry = HTTP_CLIENT.send(
                adminRequest(keycloak, requestPath + "/provisioning/retry", globalAdminToken)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, retry.statusCode(), retry.body());

        HttpResponse<String> archived = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/provisioning-failures?state=CLOSED",
                        globalAdminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, archived.statusCode(), archived.body());
        assertTrue(archived.body().contains("\"id\":\"" + failure.requestId() + "\""), archived.body());
        assertTrue(archived.body().contains("\"closureReason\":"), archived.body());

        HttpResponse<String> detail = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/mine/" + failure.requestId(), requesterToken)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"entitlementId\":\"" + failure.entitlementId() + "\""),
                detail.body());
        assertTrue(detail.body().contains("\"decisionStatus\":\"APPROVED\""), detail.body());
        assertTrue(detail.body().contains("\"provisioningStatus\":\"FAILED\""), detail.body());
        assertTrue(detail.body().contains("\"type\":\"PROVISIONING_CLOSED\""), detail.body());
    }

    private void deactivateEntitlement(
            KeycloakContainer keycloak, String globalAdminToken, String entitlementId,
            PendingProvisioningFixture pending) throws Exception {
        String path = "/realms/master/access-requests/admin/entitlements/" + entitlementId;
        HttpResponse<String> current = HTTP_CLIENT.send(
                adminRequest(keycloak, path, globalAdminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, current.statusCode(), current.body());
        assertTrue(current.body().contains("\"resourceId\":\"" + pending.request().groupId() + "\""),
                current.body());
        var version = Pattern.compile("\\\"version\\\"\\s*:\\s*(\\d+)").matcher(current.body());
        assertTrue(version.find(), current.body());
        HttpResponse<String> deactivated = HTTP_CLIENT.send(
                adminRequest(keycloak, path, globalAdminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"displayName":"%s","description":"Verifies browser-driven provisioning recovery.",
                                 "riskLevel":"LOW","approverRoleId":"%s","requestable":false,"version":%s}
                                """.formatted(pending.request().displayName(), pending.approverRoleId(),
                                version.group(1))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deactivated.statusCode(), deactivated.body());
        assertTrue(deactivated.body().contains("\"requestable\":false"), deactivated.body());
        assertTrue(deactivated.body().contains("\"resourceId\":\"" + pending.request().groupId() + "\""),
                deactivated.body());
    }

    private String createPublishedReplacementPackage(
            KeycloakContainer keycloak, String globalAdminToken, String replacementRoleId,
            String approverRoleId) throws Exception {
        String path = "/realms/master/access-requests/admin/entitlements";
        HttpResponse<String> created = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/access-packages", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"displayName":"Replacement browser entitlement",
                                 "description":"A new approval is required for the replacement role.",
                                 "riskLevel":"LOW","approverRoleId":"%s",
                                 "roleMappings":[{"type":"REALM_ROLE","roleId":"%s"}]}
                                """.formatted(approverRoleId, replacementRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        String entitlementId = responseId(created.body());
        HttpResponse<String> activated = HTTP_CLIENT.send(
                adminRequest(keycloak, path + "/" + entitlementId, globalAdminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"displayName":"Replacement browser entitlement",
                                 "description":"A new approval is required for the replacement role.",
                                 "riskLevel":"LOW","approverRoleId":"%s","requestable":true,"version":0}
                                """.formatted(approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, activated.statusCode(), activated.body());
        assertTrue(activated.body().contains("\"resourceType\":\"GROUP\""), activated.body());
        return entitlementId;
    }

    private String submitReplacementRequest(
            KeycloakContainer keycloak, String requesterToken, String replacementEntitlementId) throws Exception {
        HttpResponse<String> submitted = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/requests", requesterToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"entitlementId":"%s",
                                 "justification":"I need the replacement role under a new approval."}
                                """.formatted(replacementEntitlementId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, submitted.statusCode(), submitted.body());
        assertTrue(submitted.body().contains("\"decisionStatus\":\"PENDING\""), submitted.body());
        return responseId(submitted.body());
    }

    private void assertRoleNotGranted(
            KeycloakContainer keycloak, String globalAdminToken, String requesterId, String roleId) throws Exception {
        assertFalse(userRealmRoles(keycloak, globalAdminToken, requesterId).contains("\"id\":\"" + roleId + "\""),
                "The replacement role must not be granted without its own approval.");
    }

    private void assertRoleGranted(
            KeycloakContainer keycloak, String globalAdminToken, String requesterId, String roleId) throws Exception {
        assertTrue(userRealmRoles(keycloak, globalAdminToken, requesterId).contains("\"id\":\"" + roleId + "\""),
                "The new approval must grant the replacement role.");
    }

    private String userRealmRoles(KeycloakContainer keycloak, String globalAdminToken, String requesterId)
            throws Exception {
        HttpResponse<String> mappings = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users/" + requesterId + "/role-mappings/realm/composite",
                        globalAdminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, mappings.statusCode(), mappings.body());
        return mappings.body();
    }

    private void assertPageHeading(WebDriver driver, String heading) {
        try {
            waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                    By.xpath("//h1[normalize-space()=" + xpathLiteral(heading) + "]")));
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    "The Administration Console did not render '%s'. URL: %s. Page: %s. Browser log: %s"
                            .formatted(heading, driver.getCurrentUrl(), driver.findElement(By.tagName("body")).getText(),
                                    browserLog(driver)),
                    exception);
        }
    }

    private void assertPermissionDenied(WebDriver driver) {
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//*[normalize-space()='You do not have permission to manage access requests in this realm.']")));
    }

    private void assertCatalogLoaded(WebDriver driver, String displayName) {
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//h2[normalize-space()=" + xpathLiteral(displayName) + "]")));
    }

    private void updateEntitlement(WebDriver driver, String displayName) {
        WebDriverWait wait = waitFor(driver);
        String itemXPath = "//*[contains(@class, 'pf-v5-c-data-list__item') and .//h2[normalize-space()="
                + xpathLiteral(displayName) + "]]";
        By item = By.xpath(itemXPath);
        wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath(itemXPath + "//button[normalize-space()='Edit access policy']"))).click();

        WebElement requestable = driver.findElement(By.id("entitlement-requestable"));
        assertTrue(requestable.isEnabled(), "The package has already been reviewed and published.");
        WebElement description = driver.findElement(By.id("entitlement-description"));
        description.clear();
        description.sendKeys("Updated through the deployed Administration Console.");
        driver.findElement(By.xpath("//button[normalize-space()='Save']")).click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath(itemXPath + "//*[normalize-space()='Updated through the deployed Administration Console.']")));
    }

    private void createAndPublishAccessPackage(WebDriver driver, AdminConsoleFixture fixture, String displayName,
            boolean captureCreation) throws Exception {
        createAndPublishAccessPackage(driver, fixture, displayName, captureCreation, false);
    }

    private void createAndPublishAccessPackage(WebDriver driver, AdminConsoleFixture fixture, String displayName,
            boolean captureCreation, boolean autoApprove) throws Exception {
        WebDriverWait wait = waitFor(driver);
        wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[normalize-space()='Create access package']"))).click();
        driver.findElement(By.id("access-package-display-name")).sendKeys(displayName);
        driver.findElement(By.id("access-package-description"))
                .sendKeys("Created through the deployed Administration Console.");
        WebElement autoApprovalPolicy = driver.findElement(By.id("access-package-auto-approve"));
        assertFalse(autoApprovalPolicy.isSelected(), "Automatic approval must default to disabled.");
        if (autoApprove) {
            assertTrue(autoApprovalPolicy.isEnabled(), "LOW-risk packages must offer automatic approval.");
            autoApprovalPolicy.click();
            assertTrue(autoApprovalPolicy.isSelected());
        }
        driver.findElement(By.id("access-package-approver-search")).sendKeys(fixture.approverRoleName());
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(
                "#access-package-approver option[value='" + fixture.approverRoleId() + "']")));
        new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("access-package-approver"))))
                .selectByValue(fixture.approverRoleId());
        driver.findElement(By.id("access-package-role-search")).sendKeys(fixture.managedTargetRoleName());
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(
                "#access-package-role option[value='" + fixture.managedTargetRoleId() + "']")));
        new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("access-package-role"))))
                .selectByValue(fixture.managedTargetRoleId());
        driver.findElement(By.xpath("//button[normalize-space()='Add role']")).click();
        WebElement selectedRoles = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//*[@aria-label='Selected roles']")));
        if (captureCreation) {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'})", selectedRoles);
            AccessRequestBrowserScreenshots.capture(driver, "workflow-admin-create-access-package-roles");
            captureDialogAtTop(driver, "workflow-admin-create-access-package");
        } else if (autoApprove) {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'})", autoApprovalPolicy);
            AccessRequestBrowserScreenshots.capture(driver, "workflow-admin-auto-approve-access-package");
        }
        driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Create access package']")).click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//*[@role='dialog']//*[normalize-space()='Package roles']")));
        assertTrue(driver.findElements(By.xpath("//*[@role='dialog']//*[contains(text(),'AR_PKG_')]")).isEmpty(),
                "The internal group name must not appear in Access Requests screens.");
        WebElement requestable = wait.until(ExpectedConditions.elementToBeClickable(By.id("entitlement-requestable")));
        assertFalse(requestable.isSelected(), "A new access package must be closed until reviewed.");
        if (captureCreation) {
            wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//*[@role='dialog']//button[normalize-space()='Edit package roles']"))).click();
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("access-package-roles-form")));
            assertTrue(driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Remove role']"))
                    .isDisplayed(), "Existing package roles must be removable from the edit form.");
            AccessRequestBrowserScreenshots.capture(driver, "workflow-admin-edit-access-package-roles");
            driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Cancel']")).click();
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("entitlement-requestable")));
            requestable = wait.until(ExpectedConditions.elementToBeClickable(By.id("entitlement-requestable")));
        }
        requestable.click();
        if (captureCreation) {
            wait.until(ignored -> !new Select(driver.findElement(By.id("entitlement-approver-role")))
                    .getFirstSelectedOption().getText().equals(fixture.approverRoleId()));
            AccessRequestBrowserScreenshots.capture(driver, "workflow-admin-publish-access-package");
            captureDialogAtTop(driver, "workflow-admin-review-access-package");
        }
        driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Save']")).click();
        String itemXPath = "//*[contains(@class, 'pf-v5-c-data-list__item') and .//h2[normalize-space()="
                + xpathLiteral(displayName) + "]]";
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath(itemXPath + "//*[normalize-space()='Open for requests']")));
    }

    private void captureDialogAtTop(WebDriver driver, String name) throws Exception {
        ((JavascriptExecutor) driver).executeScript("""
                const dialog = document.querySelector('[role="dialog"]');
                const body = dialog?.querySelector('.pf-v5-c-modal-box__body');
                if (body) body.scrollTop = 0;
                """);
        AccessRequestBrowserScreenshots.capture(driver, name);
    }

    private void assertAccessPackageWasPersisted(KeycloakContainer keycloak, AdminConsoleFixture fixture,
            String displayName) throws Exception {
        HttpResponse<String> catalog = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements?page=0&size=100",
                        fixture.globalAdminToken()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, catalog.statusCode(), catalog.body());
        JsonNode entitlement = JSON.readTree(catalog.body()).path("items");
        JsonNode accessPackage = null;
        for (JsonNode item : entitlement) {
            if (displayName.equals(item.path("displayName").asText())) {
                accessPackage = item;
                break;
            }
        }
        assertTrue(accessPackage != null, "The browser-created access package must appear in the catalog.");
        assertTrue(accessPackage.path("requestable").asBoolean());
        assertEquals("GROUP", accessPackage.path("resourceType").asText());

        HttpResponse<String> details = HTTP_CLIENT.send(adminRequest(keycloak,
                "/realms/master/access-requests/admin/access-packages/" + accessPackage.path("id").asText(),
                fixture.globalAdminToken()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, details.statusCode(), details.body());
        JsonNode binding = JSON.readTree(details.body());
        assertTrue(binding.path("groupName").asText().startsWith("AR_PKG_"));
        assertTrue(binding.path("groupExists").asBoolean());
        assertTrue(binding.path("configurationValid").asBoolean());
        assertEquals(fixture.managedTargetRoleId(), binding.path("roleMappings").get(0).path("roleId").asText());
    }

    private void assertEntitlementWasPersisted(KeycloakContainer keycloak, String globalAdminToken, String displayName)
            throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements?page=0&size=100", globalAdminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode items = JSON.readTree(response.body()).path("items");
        JsonNode draft = null;
        for (JsonNode item : items) {
            if (displayName.equals(item.path("displayName").asText())) {
                draft = item;
                break;
            }
        }
        assertTrue(draft != null, "The package entitlement must remain in the catalog.");
        assertTrue(draft.path("requestable").asBoolean());
        assertEquals("Updated through the deployed Administration Console.", draft.path("description").asText());
    }

    private String xpathLiteral(String value) {
        return "'" + value.replace("'", "\\'") + "'";
    }

    private void assertPackagedAssetsLoaded(WebDriver driver) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assets = (List<Map<String, Object>>) ((JavascriptExecutor) driver).executeScript("""
                return performance.getEntriesByType('resource')
                    .filter((entry) => /\\.(?:css|js)(?:\\?.*)?$/.test(entry.name))
                    .map((entry) => ({ url: entry.name, status: entry.responseStatus }));
                """);

        assertTrue(assets.stream().map(asset -> (String) asset.get("url"))
                        .anyMatch(url -> url.contains("/resources/") && url.endsWith(".js")),
                "The Administration Console must load JavaScript from Keycloak theme resources.");
        assertTrue(assets.stream().map(asset -> (String) asset.get("url"))
                        .anyMatch(url -> url.contains("/resources/") && url.endsWith(".css")),
                "The Administration Console must load CSS from Keycloak theme resources.");
        assertFalse(assets.stream().map(asset -> (String) asset.get("url")).anyMatch(url -> url.contains(":5174/")),
                "The deployed theme must not depend on the Vite development server.");
        assertTrue(assets.stream().allMatch(asset -> {
            int status = ((Number) asset.get("status")).intValue();
            return status >= 200 && status < 400;
        }), () -> "Theme CSS and JavaScript assets must load successfully: " + assets);
    }

    private List<Map<String, Object>> apiRequests(WebDriver driver) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> requests = (List<Map<String, Object>>) ((JavascriptExecutor) driver).executeScript("""
                return performance.getEntriesByType('resource')
                    .filter((entry) => entry.name.includes('/access-requests/'))
                    .map((entry) => ({ url: entry.name, status: entry.responseStatus }));
                """);
        return requests;
    }

    private void assertApiRequestStatus(WebDriver driver, String path, int expectedStatus) {
        assertTrue(apiRequests(driver).stream().anyMatch(request -> ((String) request.get("url")).contains(path)
                        && ((Number) request.get("status")).intValue() == expectedStatus),
                () -> "Expected an API response with status %d for %s. Requests: %s"
                        .formatted(expectedStatus, path, apiRequests(driver)));
    }

    private void assertNoJavaScriptErrors(WebDriver driver) {
        List<LogEntry> severeEntries = driver.manage().logs().get(LogType.BROWSER).getAll().stream()
                .filter(entry -> entry.getLevel().intValue() >= Level.SEVERE.intValue())
                // Fetch reports expected HTTP failures as a severe browser message; the status is asserted separately.
                .filter(entry -> !entry.getMessage().contains("Failed to load resource"))
                .toList();
        assertTrue(severeEntries.isEmpty(), () -> "Browser JavaScript errors: " + severeEntries);
    }

    private WebDriverWait waitFor(WebDriver driver) {
        return new WebDriverWait(driver, Duration.ofSeconds(30));
    }

    private String tail(String value) {
        int maximumLength = 4_000;
        return value.length() <= maximumLength ? value : value.substring(value.length() - maximumLength);
    }

    private List<LogEntry> browserLog(WebDriver driver) {
        return driver.manage().logs().get(LogType.BROWSER).getAll();
    }

    private void selectAdminTheme(KeycloakContainer keycloak, String globalAdminToken) throws Exception {
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"adminTheme\":\"access-requests\"}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private void createSeedPackage(
            KeycloakContainer keycloak, String globalAdminToken, String targetRoleId, String approverRoleId) throws Exception {
        HttpResponse<String> created = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/access-packages", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "displayName":"Browser seed package",
                                  "description":"Used to verify the deployed Administration Console catalog.",
                                  "riskLevel":"LOW",
                                  "approverRoleId":"%s",
                                  "roleMappings":[{"type":"REALM_ROLE","roleId":"%s"}]
                                }
                                """.formatted(approverRoleId, targetRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
    }

    private String createEnabledUser(
            KeycloakContainer keycloak, String globalAdminToken, String username, String password) throws Exception {
        return createEnabledUser(keycloak, globalAdminToken, username, password, "", "");
    }

    private String createEnabledUser(KeycloakContainer keycloak, String globalAdminToken,
            String username, String password, String firstName, String lastName) throws Exception {
        HttpResponse<Void> created = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "username":"%s",
                                  "firstName":"%s",
                                  "lastName":"%s",
                                  "enabled":true,
                                  "credentials":[{"type":"password","value":"%s","temporary":false}]
                                }
                                """.formatted(username, firstName, lastName, password)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, created.statusCode());
        return findId(keycloak, "/admin/realms/master/users?username=" + username + "&exact=true", globalAdminToken);
    }

    private String createRealmRole(KeycloakContainer keycloak, String globalAdminToken, String roleName) throws Exception {
        HttpResponse<Void> created = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/roles", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"%s\"}".formatted(roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, created.statusCode());
        return findId(keycloak, "/admin/realms/master/roles/" + roleName, globalAdminToken);
    }

    private void assignRealmRole(
            KeycloakContainer keycloak, String globalAdminToken, String userId, String roleId, String roleName) throws Exception {
        HttpResponse<Void> assigned = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users/" + userId + "/role-mappings/realm", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "[{\"id\":\"%s\",\"name\":\"%s\"}]".formatted(roleId, roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, assigned.statusCode());
    }

    private void assignMasterRealmManagementRole(
            KeycloakContainer keycloak, String globalAdminToken, String userId, String roleName) throws Exception {
        String realmManagementClientId = findId(
                keycloak, "/admin/realms/master/clients?clientId=master-realm", globalAdminToken);
        HttpResponse<String> role = HTTP_CLIENT.send(
                adminRequest(
                                keycloak,
                                "/admin/realms/master/clients/" + realmManagementClientId + "/roles/" + roleName,
                                globalAdminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, role.statusCode());
        String roleId = responseId(role.body());
        HttpResponse<Void> assigned = HTTP_CLIENT.send(
                adminRequest(
                                keycloak,
                                "/admin/realms/master/users/" + userId + "/role-mappings/clients/"
                                        + realmManagementClientId,
                                globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "[{\"id\":\"%s\",\"name\":\"%s\"}]".formatted(roleId, roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, assigned.statusCode());
    }

    private String findId(KeycloakContainer keycloak, String path, String globalAdminToken) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(keycloak, path, globalAdminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return responseId(response.body());
    }

    private String accessToken(KeycloakContainer keycloak, String clientId, String username, String password) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder(serverUri(keycloak, "/realms/master/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=password&client_id=%s&username=%s&password=%s"
                                        .formatted(clientId, username, password)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var matcher = Pattern.compile("\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response.body());
        assertTrue(matcher.find(), "The token response must contain an access token.");
        return matcher.group(1);
    }

    private String responseId(String response) {
        var matcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
        assertTrue(matcher.find(), () -> "Expected an identifier in response: " + response);
        return matcher.group(1);
    }

    private HttpRequest.Builder adminRequest(KeycloakContainer keycloak, String path, String accessToken) {
        return HttpRequest.newBuilder(serverUri(keycloak, path)).header("Authorization", "Bearer " + accessToken);
    }

    private URI serverUri(KeycloakContainer keycloak, String path) {
        return URI.create("https://%s:%d%s".formatted(
                keycloak.getHost(), keycloak.getHttpsPort(), path));
    }

    private String adminConsoleUri() {
        return "https://keycloak:8443/admin/master/console/";
    }

    private String accountConsoleUri() {
        return "https://keycloak:8443/realms/master/account/";
    }

    private static HttpClient insecureHttpClient() {
        try {
            X509TrustManager trustAllCertificates = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // The disposable Keycloak test container uses a self-signed certificate.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // The disposable Keycloak test container uses a self-signed certificate.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllCertificates}, new SecureRandom());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder().sslContext(sslContext).sslParameters(sslParameters).build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to configure the HTTPS client for Keycloak Testcontainers.", exception);
        }
    }

    private record AdminConsoleFixture(
            String globalAdminToken,
            String managerUsername,
            String managerPassword,
            String observerUsername,
            String observerPassword,
            String managedTargetRoleId,
            String managedTargetRoleName,
            String approverRoleId,
            String approverRoleName) {
    }

    private record FailedProvisioningFixture(
            String requestId,
            String entitlementId,
            String requesterId,
            String requestClientId,
            String requesterUsername,
            String requesterPassword,
            String roleId,
            String roleName,
            String groupId,
            String displayName) {
    }

    private record PendingProvisioningFixture(
            FailedProvisioningFixture request,
            String approverId,
            String approverUsername,
            String approverRoleId,
            String approverToken) {
    }
}
