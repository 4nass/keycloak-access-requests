package ch.anass.keycloak.accessrequests.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestAdminConsoleThemeTest {

    @Test
    void packagesTheAccessRequestAdminThemeWithItsFreemarkerBootstrap() throws IOException {
        String descriptor = readResource("META-INF/keycloak-themes.json");
        assertTrue(descriptor.contains("\"access-requests\""));
        assertTrue(descriptor.contains("\"admin\""));

        String properties = readResource("theme/access-requests/admin/theme.properties");
        assertTrue(properties.contains("parent=keycloak.v2"));

        String bootstrap = readResource("theme/access-requests/admin/index.ftl");
        assertTrue(bootstrap.contains("data-page-id=\"admin\""));
        assertTrue(bootstrap.contains("src/admin/main.tsx"));
        assertTrue(bootstrap.contains("\"adminBaseUrl\""));
        assertTrue(bootstrap.contains("\"consoleBaseUrl\""));

        Properties messages = new Properties();
        messages.load(new StringReader(readResource("theme/access-requests/admin/messages/messages_en.properties")));
        assertTrue(messages.stringPropertyNames().containsAll(Set.of(
                "accessRequestsAdminCatalog",
                "accessRequestsAdminCreateEntitlement",
                "accessRequestsAdminEditEntitlement",
                "accessRequestsAdminRequestable",
                "accessRequestsAdminRiskLevel")));
        assertTrue(messages.stringPropertyNames().stream()
                .allMatch(key -> !messages.getProperty(key).isBlank()));
    }

    private static InputStream resource(String name) {
        InputStream resource = AccessRequestAdminConsoleThemeTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(resource, () -> "The provider must package " + name + ".");
        return resource;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream resource = resource(name)) {
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
