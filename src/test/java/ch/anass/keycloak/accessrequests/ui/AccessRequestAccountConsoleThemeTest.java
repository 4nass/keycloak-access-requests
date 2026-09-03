package ch.anass.keycloak.accessrequests.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestAccountConsoleThemeTest {

    @Test
    void packagesTheAccessRequestAccountThemeWithItsFreemarkerBootstrap() throws IOException {
        assertNotNull(resource("META-INF/keycloak-themes.json"));

        String descriptor = readResource("META-INF/keycloak-themes.json");
        assertTrue(descriptor.contains("\"access-requests\""));
        assertTrue(descriptor.contains("\"account\""));

        String properties = readResource("theme/access-requests/account/theme.properties");
        assertTrue(properties.contains("parent=keycloak.v3"));

        assertNotNull(resource("theme/access-requests/account/index.ftl"));

        Properties messages = new Properties();
        messages.load(new StringReader(readResource("theme/access-requests/account/messages/messages_en.properties")));
        assertEquals(Set.of(
                "accessRequestsAlreadyGranted",
                "accessRequestsApprove",
                "accessRequestsApproveEntitlement",
                "accessRequestsApproved",
                "accessRequestsApprovals",
                "accessRequestsCancel",
                "accessRequestsCancelRequest",
                "accessRequestsCanceled",
                "accessRequestsClose",
                "accessRequestsConfirmApproval",
                "accessRequestsConfirmRejection",
                "accessRequestsDecision",
                "accessRequestsDecisionComment",
                "accessRequestsGranted",
                "accessRequestsHistory",
                "accessRequestsJustification",
                "accessRequestsLoading",
                "accessRequestsLoadError",
                "accessRequestsMyRequests",
                "accessRequestsNav",
                "accessRequestsPending",
                "accessRequestsProvisioning",
                "accessRequestsReject",
                "accessRequestsRejectEntitlement",
                "accessRequestsRejected",
                "accessRequestsRequestAccess",
                "accessRequestsRequestAccessTo",
                "accessRequestsRequestDetails",
                "accessRequestsRequestPending",
                "accessRequestsRequestedBy",
                "accessRequestsResourceType",
                "accessRequestsRetry",
                "accessRequestsRisk",
                "accessRequestsRiskLabel",
                "accessRequestsSubmitRequest",
                "accessRequestsViewDetails"), messages.stringPropertyNames());
    }

    private static InputStream resource(String name) {
        InputStream resource = AccessRequestAccountConsoleThemeTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(resource, () -> "The provider must package " + name + ".");
        return resource;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream resource = resource(name)) {
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
