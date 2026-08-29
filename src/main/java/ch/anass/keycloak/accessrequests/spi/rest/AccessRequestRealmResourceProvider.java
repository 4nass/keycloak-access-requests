package ch.anass.keycloak.accessrequests.spi.rest;

import org.keycloak.services.resource.RealmResourceProvider;

public final class AccessRequestRealmResourceProvider implements RealmResourceProvider {

    private final AccessRequestRealmResource resource = new AccessRequestRealmResource();

    @Override
    public Object getResource() {
        return resource;
    }

    @Override
    public void close() {
        // This provider does not own resources.
    }
}
