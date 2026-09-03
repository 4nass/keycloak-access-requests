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
    void rendersThePackagedAccountThemeInLightAndDarkModesWithoutBrowserErrors() throws Exception {
        try (KeycloakContainer keycloak = keycloak()) {
            keycloak.start();
            configureAdminCliTokenBehavior(keycloak);
            configureAccountConsole(keycloak);

            verifyAccountConsole(keycloak, false);
            verifyAccountConsole(keycloak, true);
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

    private void configureAccountConsole(KeycloakContainer keycloak) throws Exception {
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
        createRequestableEntitlement(keycloak, accessToken(keycloak, "admin-cli"), targetRoleId, approverRoleId);
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
                assertRoutesAndNavigation(driver);
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
        driver.navigate().to(accountConsoleUri());
        WebDriverWait wait = waitFor(driver);
        try {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("username"))).sendKeys("admin");
        } catch (TimeoutException exception) {
            String pageText = driver.findElement(By.tagName("body")).getText();
            throw new AssertionError(
                    "The Keycloak login form was not rendered. URL: %s. Page: %s. Browser log: %s. Keycloak log: %s"
                            .formatted(driver.getCurrentUrl(), pageText, browserLog(driver), tail(keycloak.getLogs())),
                    exception);
        }
        driver.findElement(By.id("password")).sendKeys("admin");
        driver.findElement(By.id("kc-login")).click();
        wait.until(ExpectedConditions.urlContains("/realms/master/account/"));
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

        assertTrue(themeSupportsDarkMode, "The selected account theme must retain Keycloak dark-mode support.");
        assertEquals(darkMode, browserPrefersDarkMode, "Chrome must emulate the requested color scheme.");
        assertEquals(darkMode, darkModeClassApplied, "Keycloak must apply its dark-mode class to the custom theme.");
    }

    private void assertRoutesAndNavigation(WebDriver driver) {
        driver.navigate().to(accountConsoleUri() + "request-access");
        assertPageHeading(driver, "Request access");

        navigateWithAccountSidebar(driver, "My Requests");
        assertPageHeading(driver, "My Requests");

        navigateWithAccountSidebar(driver, "Approvals");
        assertPageHeading(driver, "Approvals");
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
                adminRequest(keycloak, "/realms/master/access-requests/admin/entitlements", managerToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "resourceType":"REALM_ROLE",
                                  "resourceId":"%s",
                                  "displayName":"%s",
                                  "description":"%s",
                                  "riskLevel":"LOW",
                                  "approverRoleId":"%s"
                                }
                                """.formatted(targetRoleId, displayName, description, approverRoleId)))
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
        assertEquals(201, created.statusCode());
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
        URI tokenEndpoint = serverUri(keycloak, "/realms/master/protocol/openid-connect/token");
        HttpResponse<String> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder(tokenEndpoint)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=password&client_id=%s&username=admin&password=admin".formatted(clientId)))
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
