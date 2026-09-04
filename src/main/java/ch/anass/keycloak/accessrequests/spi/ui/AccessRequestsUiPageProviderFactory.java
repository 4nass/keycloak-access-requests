package ch.anass.keycloak.accessrequests.spi.ui;

import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.ui.extend.UiPageProviderFactory;

import java.util.List;

/**
 * Registers the native Admin Console navigation entry. React owns the rendered page at the registered route.
 */
public final class AccessRequestsUiPageProviderFactory implements UiPageProviderFactory<ComponentModel> {

    public static final String ID = "access-requests";

    @Override
    public ComponentModel create(KeycloakSession session, ComponentModel model) {
        return model;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getHelpText() {
        return "Access request entitlement administration";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public void init(Config.Scope config) {
        // No server-side UI configuration is required.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // No post-initialization work is required.
    }

    @Override
    public void close() {
        // This stateless registration owns no resources.
    }
}
