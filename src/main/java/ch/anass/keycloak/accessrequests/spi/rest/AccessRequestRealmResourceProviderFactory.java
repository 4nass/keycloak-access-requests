package ch.anass.keycloak.accessrequests.spi.rest;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

public final class AccessRequestRealmResourceProviderFactory implements RealmResourceProviderFactory {

    static final String FACTORY_ID = "access-requests";

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new AccessRequestRealmResourceProvider();
    }

    @Override
    public void init(Config.Scope config) {
        // This provider has no configuration.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // This provider has no post-initialization work.
    }

    @Override
    public String getId() {
        return FACTORY_ID;
    }

    @Override
    public void close() {
        // This factory does not own resources.
    }
}
