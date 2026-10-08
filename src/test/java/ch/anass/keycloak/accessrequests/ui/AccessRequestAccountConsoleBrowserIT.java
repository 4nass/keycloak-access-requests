package ch.anass.keycloak.accessrequests.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.keycloak.models.utils.TimeBasedOTP;
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
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class AccessRequestAccountConsoleBrowserIT {

    private static final String ACCESS_REQUESTS_API_AUDIENCE = "access-requests-api";
    private static final String ACCOUNT_CONSOLE_CLIENT_ID = "account-console";
    private static final String TEST_OTP_SECRET = "DJmQfC73VGFhw7D4QJ8A";
    private static final String DEFAULT_KEYCLOAK_VERSION = "26.7.5";
    private static final String DEFAULT_SELENIUM_CHROME_CONTAINER = "selenium/standalone-chrome:4.45.0-20260606";
    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version", DEFAULT_KEYCLOAK_VERSION);
    private static final String KEYCLOAK_IMAGE = System.getProperty(
            "keycloak.image", "quay.io/keycloak/keycloak:" + KEYCLOAK_VERSION);
    private static final String SELENIUM_CHROME_CONTAINER = System.getProperty(
            "selenium.chrome.container", DEFAULT_SELENIUM_CHROME_CONTAINER);
    private static final Network NETWORK = Network.newNetwork();
    private static final HttpClient HTTP_CLIENT = insecureHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @Test
    void rendersThePackagedAccountThemeInLightAndDarkModesWithoutBrowserErrors() throws Exception {
        try (KeycloakContainer keycloak = keycloak()) {
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            EntitlementRoles entitlementRoles = configureAccountConsole(keycloak);
            if (AccessRequestBrowserScreenshots.enabled()) {
                captureEmptyCatalog(keycloak);
            }
            createRequestableEntitlement(keycloak, accessToken(keycloak, "admin-cli"),
                    entitlementRoles.targetRoleId(), entitlementRoles.approverRoleId());

            verifyAccountConsole(keycloak, false);
            verifyAccountConsole(keycloak, true);
        }
    }

    @Test
    void approvesACriticalRequestAfterRealOtpStepUpAndPersistsAuditEvidence() throws Exception {
        try (KeycloakContainer keycloak = keycloak()) {
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            String adminToken = accessToken(keycloak, "admin-cli");
            selectAccountTheme(keycloak, adminToken);
            addAccessRequestsAudience(keycloak, adminToken, ACCOUNT_CONSOLE_CLIENT_ID);
            addAccessRequestsAudience(keycloak, adminToken, "admin-cli");

            String approverRoleName = "otp-approver-" + UUID.randomUUID();
            String approverRoleId = createRealmRole(keycloak, adminToken, approverRoleName);
            String sourceRoleId = createRealmRole(keycloak, adminToken, "otp-source-" + UUID.randomUUID());
            String approver = "otp-approver-" + UUID.randomUUID();
            String password = "approver-password";
            String approverId = createTestUser(keycloak, adminToken, approver, password, true);
            assignRealmRole(keycloak, adminToken, approverId, approverRoleId, approverRoleName);
            String requester = "otp-requester-" + UUID.randomUUID();
            createTestUser(keycloak, adminToken, requester, "requester-password", false);
            String entitlementId = createCriticalPackage(keycloak, adminToken, sourceRoleId, approverRoleId);
            String requesterToken = accessToken(keycloak, "admin-cli", requester, "requester-password");
            HttpResponse<String> request = HTTP_CLIENT.send(
                    adminRequest(keycloak, "/realms/master/access-requests/requests", requesterToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"entitlementId":"%s","justification":"Temporary reporting task"}
                                    """.formatted(entitlementId))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, request.statusCode(), request.body());
            String requestId = responseId(request.body());

            configureOtpStepUpFlow(keycloak, adminToken);
            try (GenericContainer<?> chrome = chrome()) {
                chrome.start();
                RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
                try {
                    driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
                    logInToAccountConsole(keycloak, driver, approver, password);
                    assertFalse(driver.getPageSource().contains("name=\"otp\""),
                            "Initial Account Console login must use LoA 1 without OTP.");
                    navigateToPendingApproval(driver);
                    clickApproval(driver);
                    WebElement verify = waitFor(driver).until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//button[normalize-space()='Verify identity']")));
                    assertExpectedStepUpDenied(driver);
                    AccessRequestBrowserScreenshots.capture(driver, "workflow-approval-step-up-required");
                    verify.click();

                    WebElement otp = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.id("otp")));
                    assertTrue(driver.findElements(By.id("password")).isEmpty(),
                            "Step-up must ask for OTP, not the password again.");
                    AccessRequestBrowserScreenshots.capture(driver, "workflow-approval-step-up-otp");
                    otp.sendKeys(new TimeBasedOTP().generateTOTP(TEST_OTP_SECRET));
                    driver.findElement(By.id("kc-login")).click();

                    assertPageHeading(driver, "Approvals");
                    navigateToPendingApproval(driver);
                    clickApproval(driver);
                    waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                            By.xpath("//*[contains(text(),'Access request approved')]")));
                    assertNoJavaScriptErrors(driver);
                } finally {
                    driver.quit();
                }
            }
            HttpResponse<String> details = HTTP_CLIENT.send(
                    adminRequest(keycloak, "/realms/master/access-requests/mine/" + requestId, requesterToken)
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, details.statusCode(), details.body());
            assertEquals("APPROVED", JSON.readTree(details.body()).path("decisionStatus").asText());
            assertEquals("SUCCEEDED", JSON.readTree(details.body()).path("provisioningStatus").asText());
            HttpResponse<String> audit = HTTP_CLIENT.send(adminRequest(keycloak,
                    "/realms/master/access-requests/admin/requests/" + requestId, adminToken)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, audit.statusCode(), audit.body());
            JsonNode approval = java.util.stream.StreamSupport.stream(
                            JSON.readTree(audit.body()).path("history").spliterator(), false)
                    .filter(entry -> "REQUEST_APPROVED".equals(entry.path("type").asText()))
                    .findFirst().orElseThrow();
            assertEquals("2", approval.path("assurance").path("requiredAcr").asText());
            assertEquals(2, approval.path("assurance").path("observedLoa").asInt());
            assertTrue(approval.path("assurance").path("verifiedAt").asLong()
                    - approval.path("assurance").path("authenticatedAt").asLong() <= 300);
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

    private EntitlementRoles configureAccountConsole(KeycloakContainer keycloak) throws Exception {
        String adminToken = accessToken(keycloak, "admin-cli");
        String administratorId = findId(keycloak, "/admin/realms/master/users?username=admin&exact=true", adminToken);
        String managerRoleName = "manage-access-requests";
        String approverRoleName = "browser-approver-" + UUID.randomUUID();
        String managerRoleId = createRealmRole(keycloak, adminToken, managerRoleName);
        String approverRoleId = createRealmRole(keycloak, adminToken, approverRoleName);
        String targetRoleId = createRealmRole(keycloak, adminToken, "browser-target-" + UUID.randomUUID());

        assignRealmRole(keycloak, adminToken, administratorId, managerRoleId, managerRoleName);
        assignRealmRole(keycloak, adminToken, administratorId, approverRoleId, approverRoleName);
        addAccessRequestsAudience(keycloak, adminToken, ACCOUNT_CONSOLE_CLIENT_ID);
        selectAccountTheme(keycloak, adminToken);
        return new EntitlementRoles(targetRoleId, approverRoleId);
    }

    private void captureEmptyCatalog(KeycloakContainer keycloak) throws Exception {
        try (GenericContainer<?> chrome = chrome()) {
            chrome.start();
            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(false));
            try {
                driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
                driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
                logInToAccountConsole(keycloak, driver);
                driver.navigate().to(accountConsoleUri() + "request-access");
                assertPageHeading(driver, "Catalog");
                waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                        "//h2[normalize-space()='No access available']")));
                AccessRequestBrowserScreenshots.capture(driver, "account-catalog-empty-light");
                assertNoJavaScriptErrors(driver);
            } finally {
                driver.quit();
            }
        }
    }

    private void verifyAccountConsole(KeycloakContainer keycloak, boolean darkMode) throws Exception {
        try (GenericContainer<?> chrome = chrome()) {
            chrome.start();

            RemoteWebDriver driver = new RemoteWebDriver(webDriverUri(chrome).toURL(), chromeOptions(darkMode));
            try {
                driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
                driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));

                logInToAccountConsole(keycloak, driver);
                assertThemeMode(driver, darkMode);
                assertRoutesAndNavigation(driver, darkMode);
                assertNativeHeaderLayout(driver);
                assertPackagedAssetsLoaded(driver);
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

    private void logInToAccountConsole(KeycloakContainer keycloak, WebDriver driver) {
        logInToAccountConsole(keycloak, driver, "admin", "admin");
    }

    private void logInToAccountConsole(KeycloakContainer keycloak, WebDriver driver,
            String username, String password) {
        driver.navigate().to(accountConsoleUri());
        WebDriverWait wait = waitFor(driver);
        try {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("username"))).sendKeys(username);
        } catch (TimeoutException exception) {
            String pageText = driver.findElement(By.tagName("body")).getText();
            throw new AssertionError(
                    "The Keycloak login form was not rendered. URL: %s. Page: %s. Browser log: %s. Keycloak log: %s"
                            .formatted(driver.getCurrentUrl(), pageText, browserLog(driver), tail(keycloak.getLogs())),
                    exception);
        }
        driver.findElement(By.id("password")).sendKeys(password);
        driver.findElement(By.id("kc-login")).click();
        wait.until(ExpectedConditions.urlContains("/realms/master/account/"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#app")));
    }

    private void navigateToPendingApproval(WebDriver driver) {
        driver.navigate().to(accountConsoleUri() + "approvals");
        assertPageHeading(driver, "Approvals");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//*[normalize-space()='OTP protected access']")));
    }

    private void clickApproval(WebDriver driver) {
        waitFor(driver).until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[normalize-space()='Approve']"))).click();
        waitFor(driver).until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[normalize-space()='Confirm approval']"))).click();
    }

    private void assertThemeMode(WebDriver driver, boolean darkMode) {
        JavascriptExecutor javascript = (JavascriptExecutor) driver;
        boolean browserPrefersDarkMode = (Boolean) javascript.executeScript(
                "return window.matchMedia('(prefers-color-scheme: dark)').matches;");
        boolean themeSupportsDarkMode = (Boolean) javascript.executeScript(
                "return JSON.parse(document.getElementById('environment').textContent).darkMode;");
        boolean darkModeClassApplied = (Boolean) javascript.executeScript(
                "return document.documentElement.classList.contains('pf-v5-theme-dark');");

        assertTrue(themeSupportsDarkMode, "The selected account theme must retain Keycloak dark-mode support.");
        assertEquals(darkMode, browserPrefersDarkMode, "Chrome must emulate the requested color scheme.");
        assertEquals(darkMode, darkModeClassApplied, "Keycloak must apply its dark-mode class to the custom theme.");
    }

    private void assertNativeHeaderLayout(WebDriver driver) {
        WebElement header = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("header[data-testid='page-header']")));
        WebElement logo = header.findElement(By.tagName("img"));

        assertTrue(logo.getRect().getHeight() >= 20 && logo.getRect().getHeight() <= 60,
                "The Account Console logo must use Keycloak's masthead sizing, not its intrinsic image size.");
        assertTrue(header.getRect().getHeight() <= 120,
                "The Account Console masthead must not push the page content below an oversized logo.");
        String bodyFont = driver.findElement(By.tagName("body")).getCssValue("font-family").toLowerCase();
        assertFalse(bodyFont.contains("times new roman"),
                "PatternFly's base typography must remain loaded alongside the Account Console styles.");
    }

    private void assertRoutesAndNavigation(WebDriver driver, boolean darkMode) throws Exception {
        String suffix = darkMode ? "dark" : "light";
        driver.navigate().to(accountConsoleUri() + "request-access");
        assertPageHeading(driver, "Catalog");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//*[normalize-space()='Browser test access']")));
        AccessRequestBrowserScreenshots.capture(driver, "account-catalog-" + suffix);

        navigateWithAccountSidebar(driver, "My requests");
        assertPageHeading(driver, "My requests");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//h2[normalize-space()='No requests yet']")));
        AccessRequestBrowserScreenshots.capture(driver, "account-my-requests-" + suffix);

        navigateWithAccountSidebar(driver, "Approvals");
        assertPageHeading(driver, "Approvals");
        waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
                "//h2[normalize-space()='No approvals pending']")));
        AccessRequestBrowserScreenshots.capture(driver, "account-approvals-" + suffix);
    }

    private void navigateWithAccountSidebar(WebDriver driver, String label) {
        By link = By.xpath("//a[normalize-space()=" + xpathLiteral(label) + "]");
        List<WebElement> links = driver.findElements(link);
        if (links.isEmpty() || !links.getFirst().isDisplayed()) {
            WebElement group = waitFor(driver).until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//button[normalize-space()=" + xpathLiteral("Access requests") + "]")));
            if (!Boolean.parseBoolean(group.getAttribute("aria-expanded"))) {
                group.click();
            }
        }

        try {
            waitFor(driver).until(ExpectedConditions.elementToBeClickable(link)).click();
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    "The Account Console navigation did not expose '%s'. Page: %s. Source: %s"
                            .formatted(label, driver.findElement(By.tagName("body")).getText(), tail(driver.getPageSource())),
                    exception);
        }
    }

    private void assertPageHeading(WebDriver driver, String heading) {
        try {
            waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(
                    By.xpath("//h1[normalize-space()=" + xpathLiteral(heading) + "]")));
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    "The Account Console did not render '%s'. URL: %s. Page: %s. Browser log: %s"
                            .formatted(heading, driver.getCurrentUrl(), driver.findElement(By.tagName("body")).getText(), browserLog(driver)),
                    exception);
        }
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
                "The Account Console must load JavaScript from Keycloak theme resources.");
        assertTrue(assets.stream().map(asset -> (String) asset.get("url"))
                        .anyMatch(url -> url.contains("/resources/") && url.endsWith(".css")),
                "The Account Console must load CSS from Keycloak theme resources.");
        assertFalse(assets.stream().map(asset -> (String) asset.get("url")).anyMatch(url -> url.contains(":5173/")),
                "The deployed theme must not depend on the Vite development server.");
        assertTrue(assets.stream().allMatch(asset -> {
            int status = ((Number) asset.get("status")).intValue();
            return status >= 200 && status < 400;
        }), () -> "Theme CSS and JavaScript assets must load successfully: " + assets);
    }

    private void assertNoJavaScriptErrors(WebDriver driver) {
        List<LogEntry> severeEntries = driver.manage().logs().get(LogType.BROWSER).getAll().stream()
                .filter(entry -> entry.getLevel().intValue() >= Level.SEVERE.intValue())
                .toList();
        assertTrue(severeEntries.isEmpty(), () -> "Browser JavaScript errors: " + severeEntries);
    }

    private void assertExpectedStepUpDenied(WebDriver driver) {
        List<LogEntry> severeEntries = driver.manage().logs().get(LogType.BROWSER).getAll().stream()
                .filter(entry -> entry.getLevel().intValue() >= Level.SEVERE.intValue())
                .toList();
        assertTrue(severeEntries.stream().allMatch(entry ->
                        entry.getMessage().contains("/approve") && entry.getMessage().contains("403")),
                () -> "Only the expected pre-MFA approval denial may appear in the browser log: " + severeEntries);
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

    private void selectAccountTheme(KeycloakContainer keycloak, String adminToken) throws Exception {
        HttpResponse<Void> response = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master", adminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"accountTheme\":\"access-requests\"}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
    }

    private void createRequestableEntitlement(
            KeycloakContainer keycloak, String managerToken, String targetRoleId, String approverRoleId) throws Exception {
        String displayName = "Browser test access";
        String description = "Access used to exercise the packaged Account Console.";
        HttpResponse<String> created = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/access-packages", managerToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "displayName":"%s",
                                  "description":"%s",
                                  "riskLevel":"LOW",
                                  "approverRoleId":"%s",
                                  "roleMappings":[{"type":"REALM_ROLE","roleId":"%s"}]
                                }
                                """.formatted(displayName, description, approverRoleId, targetRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());

        String entitlementId = responseId(created.body());
        HttpResponse<String> updated = HTTP_CLIENT.send(
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements/" + entitlementId, managerToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "displayName":"%s",
                                  "description":"%s",
                                  "riskLevel":"LOW",
                                  "approverRoleId":"%s",
                                  "requestable":true,
                                  "version":0
                                }
                                """.formatted(displayName, description, approverRoleId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updated.statusCode());
    }

    private String createCriticalPackage(KeycloakContainer keycloak, String adminToken,
            String sourceRoleId, String approverRoleId) throws Exception {
        HttpResponse<String> created = postAdminJson(keycloak,
                "/realms/master/access-requests/admin/access-packages", adminToken, """
                        {"displayName":"OTP protected access","description":"Critical test package",
                         "riskLevel":"CRITICAL","approverRoleId":"%s",
                         "defaultDurationSeconds":3600,"maxDurationSeconds":14400,
                         "roleMappings":[{"type":"REALM_ROLE","roleId":"%s"}]}
                        """.formatted(approverRoleId, sourceRoleId));
        assertEquals(201, created.statusCode(), created.body());
        String entitlementId = responseId(created.body());
        HttpResponse<String> published = HTTP_CLIENT.send(adminRequest(keycloak,
                "/realms/master/access-requests/admin/entitlements/" + entitlementId, adminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""
                                {"displayName":"OTP protected access","description":"Critical test package",
                                 "riskLevel":"CRITICAL","approverRoleId":"%s","requestable":true,"version":0,
                                 "defaultDurationSeconds":3600,"maxDurationSeconds":14400}
                                """.formatted(approverRoleId))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, published.statusCode(), published.body());
        return entitlementId;
    }

    private String createTestUser(KeycloakContainer keycloak, String adminToken,
            String username, String password, boolean withOtp) throws Exception {
        var credentials = new java.util.ArrayList<Map<String, Object>>();
        credentials.add(Map.of("type", "password", "value", password, "temporary", false));
        if (withOtp) {
            credentials.add(Map.of(
                    "type", "otp", "userLabel", "Test authenticator",
                    "secretData", "{\"value\":\"" + TEST_OTP_SECRET + "\"}",
                    "credentialData", "{\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\",\"subType\":\"totp\"}"));
        }
        String body = JSON.writeValueAsString(Map.of(
                "username", username, "enabled", true, "credentials", credentials));
        HttpResponse<String> created = postAdminJson(keycloak,
                "/admin/realms/master/users", adminToken, body);
        assertEquals(201, created.statusCode(), created.body());
        return findId(keycloak, "/admin/realms/master/users?username=" + username + "&exact=true", adminToken);
    }

    private void configureOtpStepUpFlow(KeycloakContainer keycloak, String adminToken) throws Exception {
        String root = "approval-step-up-" + UUID.randomUUID();
        String authentication = root + "-authentication";
        String first = root + "-level-1";
        String second = root + "-level-2";
        assertEquals(201, postAdminJson(keycloak, "/admin/realms/master/authentication/flows", adminToken,
                JSON.writeValueAsString(Map.of("alias", root, "providerId", "basic-flow",
                        "topLevel", true, "builtIn", false))).statusCode());
        addExecution(keycloak, adminToken, root, "auth-cookie", "ALTERNATIVE");
        addSubFlow(keycloak, adminToken, root, authentication, "ALTERNATIVE");
        addSubFlow(keycloak, adminToken, authentication, first, "CONDITIONAL");
        addCondition(keycloak, adminToken, first, 1, 36000);
        addExecution(keycloak, adminToken, first, "auth-username-password-form", "REQUIRED");
        addSubFlow(keycloak, adminToken, authentication, second, "CONDITIONAL");
        addCondition(keycloak, adminToken, second, 2, 300);
        addExecution(keycloak, adminToken, second, "auth-otp-form", "REQUIRED");

        HttpResponse<String> bound = HTTP_CLIENT.send(adminRequest(keycloak, "/admin/realms/master", adminToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("browserFlow", root))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, bound.statusCode(), bound.body());
        assertEquals(2, execution(keycloak, adminToken, root, "conditional-level-of-authentication")
                .size(), "The browser flow must include both LoA conditions.");
    }

    private void addSubFlow(KeycloakContainer keycloak, String adminToken,
            String parent, String alias, String requirement) throws Exception {
        String path = "/admin/realms/master/authentication/flows/" + parent + "/executions/flow";
        HttpResponse<String> added = postAdminJson(keycloak, path, adminToken,
                JSON.writeValueAsString(Map.of("alias", alias, "type", "basic-flow")));
        assertEquals(201, added.statusCode(), added.body());
        setRequirement(keycloak, adminToken, parent, alias, requirement);
    }

    private void addExecution(KeycloakContainer keycloak, String adminToken,
            String parent, String provider, String requirement) throws Exception {
        String path = "/admin/realms/master/authentication/flows/" + parent + "/executions/execution";
        HttpResponse<String> added = postAdminJson(keycloak, path, adminToken,
                JSON.writeValueAsString(Map.of("provider", provider)));
        assertEquals(201, added.statusCode(), added.body());
        setRequirement(keycloak, adminToken, parent, provider, requirement);
    }

    private void addCondition(KeycloakContainer keycloak, String adminToken,
            String parent, int level, int maxAge) throws Exception {
        String provider = "conditional-level-of-authentication";
        addExecution(keycloak, adminToken, parent, provider, "REQUIRED");
        String executionId = execution(keycloak, adminToken, parent, provider).getFirst().path("id").asText();
        HttpResponse<String> configured = postAdminJson(keycloak,
                "/admin/realms/master/authentication/executions/" + executionId + "/config", adminToken,
                JSON.writeValueAsString(Map.of("alias", parent + "-config", "config", Map.of(
                        "loa-condition-level", Integer.toString(level),
                        "loa-max-age", Integer.toString(maxAge)))));
        assertEquals(201, configured.statusCode(), configured.body());
    }

    private void setRequirement(KeycloakContainer keycloak, String adminToken,
            String parent, String name, String requirement) throws Exception {
        ObjectNode entry = (ObjectNode) execution(keycloak, adminToken, parent, name).getFirst().deepCopy();
        entry.put("requirement", requirement);
        HttpResponse<String> updated = HTTP_CLIENT.send(adminRequest(keycloak,
                "/admin/realms/master/authentication/flows/" + parent + "/executions", adminToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(entry)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, updated.statusCode(), updated.body());
    }

    private List<JsonNode> execution(KeycloakContainer keycloak, String adminToken,
            String parent, String name) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(adminRequest(keycloak,
                "/admin/realms/master/authentication/flows/" + parent + "/executions", adminToken)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        List<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode entry : JSON.readTree(response.body())) {
            if (name.equals(entry.path("providerId").asText()) || name.equals(entry.path("displayName").asText())) {
                matches.add(entry);
            }
        }
        assertFalse(matches.isEmpty(), () -> "No " + name + " execution in " + parent + ": " + response.body());
        return matches;
    }

    private HttpResponse<String> postAdminJson(KeycloakContainer keycloak,
            String path, String adminToken, String body) throws Exception {
        return HTTP_CLIENT.send(adminRequest(keycloak, path, adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void addAccessRequestsAudience(KeycloakContainer keycloak, String adminToken, String clientId)
            throws Exception {
        ensureAccessRequestsApiClient(keycloak, adminToken);
        String clientInternalId = clientInternalId(keycloak, adminToken, clientId);

        HttpResponse<Void> mapperCreated = HTTP_CLIENT.send(
                adminRequest(
                                keycloak,
                                "/admin/realms/master/clients/" + clientInternalId + "/protocol-mappers/models",
                                adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "name":"%s-audience",
                                  "protocol":"openid-connect",
                                  "protocolMapper":"oidc-audience-mapper",
                                  "config":{
                                    "included.client.audience":"%s",
                                    "access.token.claim":"true",
                                    "id.token.claim":"false",
                                    "introspection.token.claim":"true"
                                  }
                                }
                                """.formatted(ACCESS_REQUESTS_API_AUDIENCE, ACCESS_REQUESTS_API_AUDIENCE)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, mapperCreated.statusCode());
    }

    private void ensureAccessRequestsApiClient(KeycloakContainer keycloak, String adminToken) throws Exception {
        HttpResponse<Void> created = HTTP_CLIENT.send(
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
        assertTrue(created.statusCode() == 201 || created.statusCode() == 409,
                "The API audience client must be created once or already exist.");
    }

    private String clientInternalId(KeycloakContainer keycloak, String adminToken, String clientId) throws Exception {
        return findId(keycloak, "/admin/realms/master/clients?clientId=" + clientId, adminToken);
    }

    private String createRealmRole(KeycloakContainer keycloak, String adminToken, String roleName) throws Exception {
        HttpResponse<Void> created = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/roles", adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"%s\"}".formatted(roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, created.statusCode());
        return findId(keycloak, "/admin/realms/master/roles/" + roleName, adminToken);
    }

    private void assignRealmRole(
            KeycloakContainer keycloak, String adminToken, String userId, String roleId, String roleName) throws Exception {
        HttpResponse<Void> assigned = HTTP_CLIENT.send(
                adminRequest(keycloak, "/admin/realms/master/users/" + userId + "/role-mappings/realm", adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "[{\"id\":\"%s\",\"name\":\"%s\"}]".formatted(roleId, roleName)))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, assigned.statusCode());
    }

    private String findId(KeycloakContainer keycloak, String path, String adminToken) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                adminRequest(keycloak, path, adminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var matcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response.body());
        assertTrue(matcher.find(), () -> "Expected an identifier in response: " + response.body());
        return matcher.group(1);
    }

    private String accessToken(KeycloakContainer keycloak, String clientId) throws Exception {
        return accessToken(keycloak, clientId, "admin", "admin");
    }

    private String accessToken(KeycloakContainer keycloak, String clientId,
            String username, String password) throws Exception {
        URI tokenEndpoint = serverUri(keycloak, "/realms/master/protocol/openid-connect/token");
        HttpResponse<String> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder(tokenEndpoint)
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

    private String accountConsoleUri() {
        return "https://keycloak:8443/realms/master/account/";
    }

    private record EntitlementRoles(String targetRoleId, String approverRoleId) {
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
}
