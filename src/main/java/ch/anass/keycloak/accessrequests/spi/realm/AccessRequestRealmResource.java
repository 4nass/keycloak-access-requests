package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.service.CatalogService;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaEntitlementRepository;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class AccessRequestRealmResource {

    private final KeycloakSession session;
    private final Supplier<CatalogService> catalogService;

    AccessRequestRealmResource(KeycloakSession session) {
        this(session, () -> new CatalogService(new JpaEntitlementRepository(
                Objects.requireNonNull(
                        session.getProvider(JpaConnectionProvider.class),
                        "Keycloak JPA connection provider must not be null")
                        .getEntityManager())));
    }

    AccessRequestRealmResource(KeycloakSession session, Supplier<CatalogService> catalogService) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService must not be null");
    }

    @GET
    @Path("catalog")
    @Produces(MediaType.APPLICATION_JSON)
    public CatalogResponse catalog(
            @QueryParam("type") ResourceType resourceType,
            @QueryParam("search") String search,
            @QueryParam("riskLevel") RiskLevel riskLevel,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        RealmModel realm = authenticate();
        CatalogPage catalogPage;
        try {
            catalogPage = catalogService.get().findRequestable(
                    new CatalogQuery(realm.getId(), resourceType, search, riskLevel, page, size));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage(), exception);
        }
        return CatalogResponse.from(catalogPage);
    }

    @OPTIONS
    @Path("catalog")
    public Response catalogOptions() {
        return Response.noContent()
                .header("Allow", "GET, OPTIONS")
                .build();
    }

    private RealmModel authenticate() {
        RealmModel realm = Objects.requireNonNull(session.getContext().getRealm(), "realm must not be null");
        AuthenticationManager.AuthResult authentication = new AppAuthManager.BearerTokenAuthenticator(session)
                .setRealm(realm)
                .setUriInfo(session.getContext().getUri())
                .setConnection(session.getContext().getConnection())
                .setHeaders(session.getContext().getRequestHeaders())
                .authenticate();
        if (authentication == null || authentication.getUser() == null) {
            throw new NotAuthorizedException("Bearer");
        }
        return realm;
    }

    public record CatalogResponse(List<CatalogItemResponse> items, int page, int size, long total) {

        private static CatalogResponse from(CatalogPage page) {
            return new CatalogResponse(
                    page.items().stream().map(CatalogItemResponse::from).toList(),
                    page.page(),
                    page.size(),
                    page.total());
        }
    }

    public record CatalogItemResponse(
            String id,
            ResourceType type,
            String name,
            String description,
            RiskLevel riskLevel) {

        private static CatalogItemResponse from(Entitlement entitlement) {
            return new CatalogItemResponse(
                    entitlement.id(),
                    entitlement.resourceType(),
                    entitlement.displayName(),
                    entitlement.description(),
                    entitlement.riskLevel());
        }
    }
}
