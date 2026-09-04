package ch.anass.keycloak.accessrequests.spi.ui;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.services.ui.extend.UiPageProviderFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestsUiPageProviderFactoryTest {

    @Test
    void registersTheNativeAdministrationConsoleNavigationEntry() throws IOException {
        AccessRequestsUiPageProviderFactory factory = new AccessRequestsUiPageProviderFactory();

        assertInstanceOf(UiPageProviderFactory.class, factory);
        assertEquals(AccessRequestsUiPageProviderFactory.ID, factory.getId());
        assertEquals("Access request entitlement administration", factory.getHelpText());
        assertTrue(factory.getConfigProperties().isEmpty());
        ComponentModel model = new ComponentModel();
        assertSame(model, factory.create(null, model));

        InputStream registration = getClass().getClassLoader().getResourceAsStream(
                "META-INF/services/org.keycloak.services.ui.extend.UiPageProviderFactory");
        assertNotNull(registration);
        try (registration) {
            assertEquals(AccessRequestsUiPageProviderFactory.class.getName(), new String(
                    registration.readAllBytes(), StandardCharsets.UTF_8).trim());
        }
    }
}
