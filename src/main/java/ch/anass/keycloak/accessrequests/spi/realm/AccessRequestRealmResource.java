package ch.anass.keycloak.accessrequests.spi.realm;

import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;

import java.util.Objects;

public final class AccessRequestRealmResource {

    private final KeycloakSession session;

    AccessRequestRealmResource(KeycloakSession session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
    }

    @OPTIONS
    @Path("catalog")
    public Response catalogOptions() {
        return Response.noContent()
                .header("Allow", "GET, OPTIONS")
                .build();
    }
}
