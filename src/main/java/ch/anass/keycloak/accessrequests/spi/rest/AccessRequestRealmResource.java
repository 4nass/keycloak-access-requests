package ch.anass.keycloak.accessrequests.spi.rest;

import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;

import java.util.Objects;

public final class AccessRequestRealmResource {

    private final KeycloakSession session;

    AccessRequestRealmResource(KeycloakSession session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
    }

    @OPTIONS
    public Response options() {
        return Response.noContent().build();
    }
}
