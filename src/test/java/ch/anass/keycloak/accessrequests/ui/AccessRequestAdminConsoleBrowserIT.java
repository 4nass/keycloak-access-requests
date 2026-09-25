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

    private static final String DEFAULT_KEYCLOAK_VERSION = "26.7.3";
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
            AdminConsoleFixture fixture = configureAdminConsole(keycloak);

            verifyCatalogManagement(keycloak, fixture, false, true);
            verifyCatalogManagement(keycloak, fixture, true, false);
            verifyCatalogAccessDenied(keycloak, fixture);
        }
    }

    @Test
    void completesAccessRequestWorkflowAcrossBothConsoles() throws Exception {
        try (KeycloakContainer keycloak = keycloak(); GenericContainer<?> chrome = chrome()) {
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            AdminConsoleFixture manager = configureAdminConsole(keycloak);
            String adminToken = manager.globalAdminToken();
            String requestClientId = createRequestTestClient(keycloak, adminToken);
            enableAccountConsoleForAccessRequests(keycloak, adminToken);

            String suffix = UUID.randomUUID().toString();
            String roleName = "browser-target-" + suffix;
            String entitlementName = "Browser workflow entitlement " + suffix.substring(0, 8);
            String approverRoleName = "browser-approver-" + suffix;
            String roleId = createRealmRole(keycloak, adminToken, roleName);
            String approverRoleId = createRealmRole(keycloak, adminToken, approverRoleName);
            String requesterUsername = "browser-requester-" + suffix;
            String approverUsername = "browser-approver-user-" + suffix;
            String requesterPassword = "browser-requester-password";
            String approverPassword = "browser-approver-password";
            String requesterId = createEnabledUser(keycloak, adminToken, requesterUsername, requesterPassword);
            String approverId = createEnabledUser(keycloak, adminToken, approverUsername, approverPassword);
            assignRealmRole(keycloak, adminToken, approverId, approverRoleId, approverRoleName);
            assertRoleNotGranted(keycloak, adminToken, requesterId, roleId);

            AdminConsoleFixture workflowCatalog = new AdminConsoleFixture(
                    adminToken, manager.managerUsername(), manager.managerPassword(),
                    manager.observerUsername(), manager.observerPassword(), roleId, approverRoleId);
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, manager.managerUsername(), manager.managerPassword());
                openAccessRequests(driver);
                createEntitlement(driver, workflowCatalog, entitlementName);
                updateEntitlement(driver, entitlementName);
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
                row.findElement(By.xpath(".//button[normalize-space()='Request access']")).click();
                driver.findElement(By.id("access-request-justification")).sendKeys(justification);
                driver.findElement(By.xpath("//*[@role='dialog']//button[normalize-space()='Submit request']")).click();
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//*[@id='requestable-entitlement-" + entitlementId
                                + "']//*[normalize-space()='Request pending']")));
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

            driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAccountConsole(keycloak, driver, requesterUsername, requesterPassword, "my-requests");
                WebElement row = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.id("access-request-" + requestId)));
                assertTrue(row.getText().contains(entitlementName));
                assertTrue(row.getText().contains("Approved"));
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }

            driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, manager.managerUsername(), manager.managerPassword());
                driver.navigate().to(adminConsoleUri() + "#/master/access-requests/requests/" + requestId);
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                        By.xpath("//h2[normalize-space()='History']")));
                assertAuditDetailValue(driver, "Requester", requesterId);
                assertAuditDetailValue(driver, "Decision status", "Approved");
                assertAuditDetailValue(driver, "Provisioning status", "Succeeded");
                assertAuditHistoryActor(driver, "Requested", requesterId);
                assertAuditHistoryActor(driver, "Approved", approverId);
                assertAuditHistoryActor(driver, "Provisioning started", approverId);
                assertAuditHistoryActor(driver, "Provisioning succeeded", approverId);
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
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
        driver.navigate().to("https://keycloak:8443/realms/master/account/" + route);
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
        assertEquals(4, history.size(), "The completed request must have a complete immutable audit history.");
        assertEquals("REQUEST_CREATED", history.get(0).path("type").asText());
        assertEquals(requesterId, history.get(0).path("actorId").asText());
        assertEquals("REQUEST_APPROVED", history.get(1).path("type").asText());
        assertEquals("PROVISIONING_STARTED", history.get(2).path("type").asText());
        assertEquals("PROVISIONING_SUCCEEDED", history.get(3).path("type").asText());
        for (int index = 1; index < history.size(); index++) {
            assertEquals(approverId, history.get(index).path("actorId").asText());
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

                    WebElement requestFilter = driver.findElement(By.id("audit-request-id"));
                    requestFilter.sendKeys(pending.request().requestId());
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(requestLink));
                    assertTrue(driver.findElement(By.tagName("body")).getText().contains("Approved"));
                    assertTrue(driver.findElements(requestLink).size() >= 1,
                            "An audit row must link to the request detail.");
                    driver.findElements(requestLink).getFirst().click();
                    waitFor(driver).until(ExpectedConditions.urlContains(
                            "/master/access-requests/requests/" + pending.request().requestId()));
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                            By.xpath("//h2[normalize-space()='History']")));
                    assertAuditDetailValue(driver, "Requester", pending.request().requesterId());
                    assertAuditDetailValue(driver, "Decision status", "Approved");
                    assertAuditDetailValue(driver, "Provisioning status", "Failed");
                    assertAuditHistoryActor(driver, "Requested", pending.request().requesterId());
                    assertAuditHistoryActor(driver, "Approved", pending.approverId());
                    assertAuditHistoryActor(driver, "Provisioning failed", pending.approverId());
                    assertApiRequestStatus(driver,
                            "/access-requests/admin/requests/" + pending.request().requestId(), 200);
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

    private void assertAuditHistoryActor(WebDriver driver, String eventType, String actorId) {
        By entries = By.cssSelector("[aria-label='History'] li.pf-v5-c-data-list__item");
        waitFor(driver).until(page -> page.findElements(entries).stream()
                .map(WebElement::getText)
                .anyMatch(text -> text.contains(eventType) && text.contains("Actor ID: " + actorId)));
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
                        firstServer, fixture.globalAdminToken(), failure, "RESOURCE_MISSING");
                assertRequesterStateAndHistory(firstServer, failure, "FAILED",
                        "REQUEST_APPROVED", "PROVISIONING_STARTED", "PROVISIONING_FAILED");
                String replacementRoleId = createRealmRole(
                        firstServer, fixture.globalAdminToken(), failure.roleName());
                restoreOriginalRoleIdForTest(postgres, failure, replacementRoleId);
            }

            // Restart to clear Keycloak's role cache after the fixture-only database repair.
            try (KeycloakContainer keycloak = keycloakWithPostgres()) {
                keycloak.start();
                try (GenericContainer<?> chrome = chrome()) {
                    chrome.start();
                    RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
                    try {
                        configureDriver(driver);
                        logInToAdminConsole(keycloak, driver, fixture.managerUsername(), fixture.managerPassword());
                        openAccessRequests(driver);
                        openFailedProvisioning(driver);
                        assertPageHeading(driver, "Failed provisioning");
                        retryFailedProvisioningInBrowser(
                                driver, failure, "The original resource is missing.");
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
                    assertPageHeading(driver, "Failed provisioning");
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
            String newEntitlementId = createPublishedReplacementEntitlement(
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
        String globalAdminToken = accessToken(keycloak, "admin-cli", "admin", "admin");
        selectAdminTheme(keycloak, globalAdminToken);

        String managerUsername = "catalog-manager-" + UUID.randomUUID();
        String managerPassword = "catalog-manager-password";
        String observerUsername = "catalog-observer-" + UUID.randomUUID();
        String observerPassword = "catalog-observer-password";
        String managerUserId = createEnabledUser(keycloak, globalAdminToken, managerUsername, managerPassword);
        String observerUserId = createEnabledUser(keycloak, globalAdminToken, observerUsername, observerPassword);

        String managerRoleName = "manage-access-requests";
        String managerRoleId = createRealmRole(keycloak, globalAdminToken, managerRoleName);
        String approverRoleId = createRealmRole(keycloak, globalAdminToken, "catalog-approver-" + UUID.randomUUID());
        String seedTargetRoleId = createRealmRole(keycloak, globalAdminToken, "catalog-seed-target-" + UUID.randomUUID());
        String managedTargetRoleId = createRealmRole(keycloak, globalAdminToken, "catalog-managed-target-" + UUID.randomUUID());

        assignRealmRole(keycloak, globalAdminToken, managerUserId, managerRoleId, managerRoleName);
        assignMasterRealmManagementRole(keycloak, globalAdminToken, managerUserId, "view-realm");
        assignMasterRealmManagementRole(keycloak, globalAdminToken, observerUserId, "view-realm");
        createSeedEntitlement(keycloak, globalAdminToken, seedTargetRoleId, approverRoleId);

        return new AdminConsoleFixture(
                globalAdminToken,
                managerUsername,
                managerPassword,
                observerUsername,
                observerPassword,
                managedTargetRoleId,
                approverRoleId);
    }

    private void verifyCatalogManagement(
            KeycloakContainer keycloak, AdminConsoleFixture fixture, boolean darkMode, boolean exerciseCatalogWorkflow)
            throws Exception {
        try (GenericContainer<?> chrome = chrome()) {
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(darkMode));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, fixture.managerUsername(), fixture.managerPassword());
                assertThemeMode(driver, darkMode);
                openAccessRequests(driver);
                assertPageHeading(driver, "Access requests");
                assertCatalogLoaded(driver, "Browser seed entitlement");

                if (exerciseCatalogWorkflow) {
                    String displayName = "Browser managed entitlement " + UUID.randomUUID();
                    createEntitlement(driver, fixture, displayName);
                    updateEntitlement(driver, displayName);
                    assertEntitlementWasPersisted(keycloak, fixture.globalAdminToken(), displayName);
                }

                openFailedProvisioning(driver);
                assertPageHeading(driver, "Failed provisioning");
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                        "//h2[normalize-space()='There are no failed provisioning requests.']")));
                assertApiRequestStatus(driver, "/access-requests/admin/provisioning-failures", 200);

                assertPackagedAssetsLoaded(driver);
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
        }
    }

    private void verifyCatalogAccessDenied(KeycloakContainer keycloak, AdminConsoleFixture fixture) throws Exception {
        try (GenericContainer<?> chrome = chrome()) {
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                configureDriver(driver);
                logInToAdminConsole(keycloak, driver, fixture.observerUsername(), fixture.observerPassword());
                openAccessRequestsDirectly(driver);
                assertPageHeading(driver, "You do not have permission to manage access requests in this realm.");
                assertTrue(driver.findElements(By.xpath("//button[normalize-space()='Create entitlement']")).isEmpty(),
                        "An administrator without manage-access-requests must not see catalog write controls.");
                assertApiRequestStatus(driver, "/access-requests/admin/capabilities", 403);
                driver.navigate().to(adminConsoleUri() + "#/master/access-requests/provisioning-failures");
                assertPageHeading(driver, "You do not have permission to manage access requests in this realm.");
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
        By failedProvisioning = By.xpath("//a[normalize-space()='Failed provisioning']");
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
        assertTrue(item.getText().contains(failure.requesterId()));
        assertTrue(item.getText().contains(expectedCause));
        assertFalse(item.getText().contains("The configured Keycloak role no longer exists."));
        item.findElement(By.xpath(".//button[normalize-space()='Retry provisioning']")).click();

        By dialog = By.xpath("//*[@role='dialog' and .//*[normalize-space()='" + failure.requestId() + "']]");
        wait.until(ExpectedConditions.visibilityOfElementLocated(dialog));
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
        assertTrue(item.getText().contains("The original resource is missing."));
        item.findElement(By.xpath(".//button[normalize-space()='Close failure']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("provisioning-closure-reason")))
                .sendKeys("The original role was deleted; a newly approved request targets its replacement.");
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//*[@role='dialog']//button[normalize-space()='Close failure']"))).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//*[contains(@class, 'pf-v5-c-alert__title') and "
                        + "contains(normalize-space(.), 'The failure was closed without granting access.')]")));
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                "//button[normalize-space()='Closed failures']"))).click();
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
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"REALM_ROLE","resourceId":"%s","displayName":"%s",
                                 "description":"Verifies browser-driven provisioning recovery.","riskLevel":"LOW",
                                 "approverRoleId":"%s"}
                                """.formatted(targetRoleId, displayName, approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, createdEntitlement.statusCode(), createdEntitlement.body());
        String entitlementId = responseId(createdEntitlement.body());
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
                        targetRoleId, targetRoleName, displayName),
                approverId, approverRoleId, approverToken);
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
                        + "/role-mappings/realm", globalAdminToken).GET().build(),
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
                        + "/role-mappings/realm", globalAdminToken).GET().build(),
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
        assertTrue(current.body().contains("\"resourceId\":\"" + pending.request().roleId() + "\""),
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
        assertTrue(deactivated.body().contains("\"resourceId\":\"" + pending.request().roleId() + "\""),
                deactivated.body());
    }

    private String createPublishedReplacementEntitlement(
            KeycloakContainer keycloak, String globalAdminToken, String replacementRoleId,
            String approverRoleId) throws Exception {
        String path = "/realms/master/access-requests/admin/entitlements";
        HttpResponse<String> created = HTTP_CLIENT.send(
                adminRequest(keycloak, path, globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"resourceType":"REALM_ROLE","resourceId":"%s",
                                 "displayName":"Replacement browser entitlement",
                                 "description":"A new approval is required for the replacement role.",
                                 "riskLevel":"LOW","approverRoleId":"%s"}
                                """.formatted(replacementRoleId, approverRoleId)))
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
        assertTrue(activated.body().contains("\"resourceId\":\"" + replacementRoleId + "\""), activated.body());
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
                adminRequest(keycloak, "/admin/realms/master/users/" + requesterId + "/role-mappings/realm",
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

    private void assertCatalogLoaded(WebDriver driver, String displayName) {
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//h2[normalize-space()=" + xpathLiteral(displayName) + "]")));
    }

    private void createEntitlement(WebDriver driver, AdminConsoleFixture fixture, String displayName) {
        WebDriverWait wait = waitFor(driver);
        wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[normalize-space()='Create entitlement']"))).click();
        new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("entitlement-resource-id"))))
                .selectByValue(fixture.managedTargetRoleId());
        driver.findElement(By.id("entitlement-display-name")).sendKeys(displayName);
        driver.findElement(By.id("entitlement-description")).sendKeys("Created through the deployed Administration Console.");
        new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("entitlement-approver-role"))))
                .selectByValue(fixture.approverRoleId());
        driver.findElement(By.xpath("//button[normalize-space()='Save']")).click();

        try {
            wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.xpath("//h2[normalize-space()=" + xpathLiteral(displayName) + "]")));
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    "The browser did not display the created entitlement. Page: %s. Requests: %s. Browser log: %s"
                            .formatted(driver.findElement(By.tagName("body")).getText(), apiRequests(driver), browserLog(driver)),
                    exception);
        }
    }

    private void updateEntitlement(WebDriver driver, String displayName) {
        WebDriverWait wait = waitFor(driver);
        String itemXPath = "//*[contains(@class, 'pf-v5-c-data-list__item') and .//h2[normalize-space()="
                + xpathLiteral(displayName) + "]]";
        By item = By.xpath(itemXPath);
        wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath(itemXPath + "//button[normalize-space()='Edit entitlement']"))).click();

        WebElement requestable = wait.until(ExpectedConditions.elementToBeClickable(By.id("entitlement-requestable")));
        if (!requestable.isSelected()) {
            requestable.click();
        }
        driver.findElement(By.xpath("//button[normalize-space()='Save']")).click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath(itemXPath + "//*[normalize-space()='Open for requests']")));
    }

    private void assertEntitlementWasPersisted(KeycloakContainer keycloak, String globalAdminToken, String displayName)
            throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements?page=0&size=100", globalAdminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"displayName\":\"" + displayName + "\""));
        assertTrue(response.body().contains("\"requestable\":true"));
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

    private void createSeedEntitlement(
            KeycloakContainer keycloak, String globalAdminToken, String targetRoleId, String approverRoleId) throws Exception {
        HttpResponse<String> created = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements", globalAdminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "resourceType":"REALM_ROLE",
                                  "resourceId":"%s",
                                  "displayName":"Browser seed entitlement",
                                  "description":"Used to verify the deployed Administration Console catalog.",
                                  "riskLevel":"LOW",
                                  "approverRoleId":"%s"
                                }
                                """.formatted(targetRoleId, approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
    }

    private String createEnabledUser(
            KeycloakContainer keycloak, String globalAdminToken, String username, String password) throws Exception {
        HttpResponse<Void> created = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users", globalAdminToken)
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
            String approverRoleId) {
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
            String displayName) {
    }

    private record PendingProvisioningFixture(
            FailedProvisioningFixture request,
            String approverId,
            String approverRoleId,
            String approverToken) {
    }
}
