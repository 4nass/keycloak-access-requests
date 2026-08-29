package ch.anass.keycloak.accessrequests.spi.rest;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public final class AccessRequestRealmResourceProvider implements RealmResourceProvider {

    private final AccessRequestRealmResource resource;

    AccessRequestRealmResourceProvider(KeycloakSession session) {
        this.resource = new AccessRequestRealmResource(session);
    }

    @Override
    public Object getResource() {
        return resource;
    }

    @Override
    public void close() {
        // This provider does not own resources.
    }
}
