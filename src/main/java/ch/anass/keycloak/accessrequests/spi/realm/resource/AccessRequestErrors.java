package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.spi.realm.dto.ApiDto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

final class AccessRequestErrors {

    private AccessRequestErrors() {
    }

    static Response error(Response.Status status, String code, String message, String requestId) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(code, message, requestId))
                .build();
    }
}
