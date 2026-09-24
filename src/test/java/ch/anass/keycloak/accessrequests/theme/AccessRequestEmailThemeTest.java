package ch.anass.keycloak.accessrequests.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestEmailThemeTest {

    private static final String THEME_ROOT = "theme/access-requests/email/";
    private static final List<String> SUPPORTED_LOCALES = List.of("en", "fr", "de", "es");
    private static final List<EmailTemplate> TEMPLATES = List.of(
            new EmailTemplate("access-request-submitted", "accessRequestSubmitted"),
            new EmailTemplate("access-request-approved", "accessRequestApproved"),
            new EmailTemplate("access-request-rejected", "accessRequestRejected"),
            new EmailTemplate("access-request-provisioning-failed", "accessRequestProvisioningFailed"),
            new EmailTemplate("access-request-provisioning-closed", "accessRequestProvisioningClosed"));

    @Test
    void packagesTheAccessRequestsEmailThemeAsPartOfTheProviderArchive() throws IOException {
        String descriptor = readResource("META-INF/keycloak-themes.json");
        assertTrue(descriptor.contains("\"access-requests\""));
        assertTrue(descriptor.contains("\"email\""));

        Properties properties = properties(THEME_ROOT + "theme.properties");
        assertEquals("keycloak", properties.getProperty("parent"));
        assertEquals(String.join(",", SUPPORTED_LOCALES), properties.getProperty("locales"));
    }

    @ParameterizedTest
    @MethodSource("templates")
    void providesKeycloakHtmlAndPlainTextTemplatesForEveryNotification(EmailTemplate template) throws IOException {
        String html = readResource(THEME_ROOT + "html/" + template.fileName() + ".ftl");
        assertTrue(html.contains("<#import \"template.ftl\" as layout>"));
        assertTrue(html.contains("<@layout.emailLayout>"));
        assertTrue(html.contains("kcSanitize(msg(\"" + template.messagePrefix() + "BodyHtml\""));

        String text = readResource(THEME_ROOT + "text/" + template.fileName() + ".ftl");
        assertTrue(text.contains("msg(\"" + template.messagePrefix() + "Body\""));
    }

    @ParameterizedTest
    @MethodSource("supportedLocales")
    void localizesEveryEmailSubjectAndBodyInEachSupportedLocale(String locale) throws IOException {
        Properties messages = properties(THEME_ROOT + "messages/messages_" + locale + ".properties");

        assertTrue(messages.stringPropertyNames().containsAll(expectedMessageKeys()));
        assertTrue(expectedMessageKeys().stream().allMatch(key -> !messages.getProperty(key).isBlank()));
    }

    @Test
    void doesNotExposeTheInternalClosureReasonToEmailRecipients() throws IOException {
        String template = "access-request-provisioning-closed.ftl";
        assertFalse(readResource(THEME_ROOT + "html/" + template).contains("event.comment"));
        assertFalse(readResource(THEME_ROOT + "text/" + template).contains("event.comment"));
    }

    private static Stream<EmailTemplate> templates() {
        return TEMPLATES.stream();
    }

    private static Stream<String> supportedLocales() {
        return SUPPORTED_LOCALES.stream();
    }

    private static Set<String> expectedMessageKeys() {
        return TEMPLATES.stream()
                .flatMap(template -> Stream.of(
                        template.messagePrefix() + "Subject",
                        template.messagePrefix() + "Body",
                        template.messagePrefix() + "BodyHtml",
                        "accessRequestResourceLabel",
                        "accessRequestJustificationLabel"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Properties properties(String name) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(readResource(name)));
        return properties;
    }

    private static InputStream resource(String name) {
        InputStream resource = AccessRequestEmailThemeTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(resource, () -> "The provider must package " + name + ".");
        return resource;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream resource = resource(name)) {
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record EmailTemplate(String fileName, String messagePrefix) {
    }
}
