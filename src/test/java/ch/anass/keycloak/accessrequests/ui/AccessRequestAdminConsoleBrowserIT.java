package ch.anass.keycloak.accessrequests.ui;

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
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
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
import java.time.Duration;
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
    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version", DEFAULT_KEYCLOAK_VERSION);
    private static final String KEYCLOAK_IMAGE = System.getProperty(
            "keycloak.image", "quay.io/keycloak/keycloak:" + KEYCLOAK_VERSION);
    private static final String SELENIUM_CHROME_CONTAINER = System.getProperty(
            "selenium.chrome.container", DEFAULT_SELENIUM_CHROME_CONTAINER);
    private static final Network NETWORK = Network.newNetwork();
    private static final HttpClient HTTP_CLIENT = insecureHttpClient();

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

    private KeycloakContainer keycloak() {
        return new KeycloakContainer(KEYCLOAK_IMAGE)
                .withNetwork(NETWORK)
                .withNetworkAliases("keycloak")
                .withEnv("KC_HOSTNAME", "keycloak")
                .withAdminUsername("admin")
                .withAdminPassword("admin")
                .useTls()
                .withFeaturesEnabled("declarative-ui")
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
                openAccessRequests(driver);
                assertPageHeading(driver, "You do not have permission to manage access requests in this realm.");
                assertTrue(driver.findElements(By.xpath("//button[normalize-space()='Create entitlement']")).isEmpty(),
                        "An administrator without manage-access-requests must not see catalog write controls.");
                assertApiRequestStatus(driver, "/access-requests/admin/capabilities", 403);
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
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("entitlement-resource-id")))
                .sendKeys(fixture.managedTargetRoleId());
        driver.findElement(By.id("entitlement-display-name")).sendKeys(displayName);
        driver.findElement(By.id("entitlement-description")).sendKeys("Created through the deployed Administration Console.");
        driver.findElement(By.id("entitlement-approver-role")).sendKeys(fixture.approverRoleId());
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
                By.xpath(itemXPath + "//*[normalize-space()='Active']")));
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
}
