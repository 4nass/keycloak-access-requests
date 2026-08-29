package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.CatalogEntry;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogResult;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.service.CatalogService;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestRepository;
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
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

import java.util.List;
import java.util.Objects;

public final class AccessRequestRealmResource {

    private static final String ACCESS_REQUESTS_API_AUDIENCE = "access-requests-api";

    private final KeycloakSession session;

    AccessRequestRealmResource(KeycloakSession session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
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
        AuthenticatedRequest authenticatedRequest = authenticate();
        CatalogResult catalogResult;
        try {
            catalogResult = catalogService(authenticatedRequest).findRequestable(
                    new CatalogQuery(
                            authenticatedRequest.realm().getId(), resourceType, search, riskLevel, page, size),
                    authenticatedRequest.user().getId());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage(), exception);
        }
        return CatalogResponse.from(catalogResult);
    }

    @OPTIONS
    @Path("catalog")
    public Response catalogOptions() {
        return Response.noContent()
                .header("Allow", "GET, OPTIONS")
                .build();
    }

    private AuthenticatedRequest authenticate() {
        RealmModel realm = Objects.requireNonNull(session.getContext().getRealm(), "realm must not be null");
        AuthenticationManager.AuthResult authentication = new AppAuthManager.BearerTokenAuthenticator(session)
                .setRealm(realm)
                .setUriInfo(session.getContext().getUri())
                .setConnection(session.getContext().getConnection())
                .setHeaders(session.getContext().getRequestHeaders())
                .setAudience(ACCESS_REQUESTS_API_AUDIENCE)
                .authenticate();
        if (authentication == null || authentication.getUser() == null) {
            throw new NotAuthorizedException("Bearer");
        }
        return new AuthenticatedRequest(realm, authentication.getUser());
    }

    private CatalogService catalogService(AuthenticatedRequest authenticatedRequest) {
        var entityManager = Objects.requireNonNull(
                session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null")
                .getEntityManager();
        return new CatalogService(
                new JpaEntitlementRepository(entityManager),
                new JpaAccessRequestRepository(entityManager),
                new KeycloakEffectiveAccessChecker(
                        session, authenticatedRequest.realm(), authenticatedRequest.user()));
    }

    public record CatalogResponse(List<CatalogItemResponse> items, int page, int size, long total) {

        private static CatalogResponse from(CatalogResult page) {
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
            RiskLevel riskLevel,
            boolean alreadyGranted,
            boolean pendingRequest) {

        private static CatalogItemResponse from(CatalogEntry entry) {
            return new CatalogItemResponse(
                    entry.entitlement().id(),
                    entry.entitlement().resourceType(),
                    entry.entitlement().displayName(),
                    entry.entitlement().description(),
                    entry.entitlement().riskLevel(),
                    entry.alreadyGranted(),
                    entry.pendingRequest());
        }
    }

    private record AuthenticatedRequest(RealmModel realm, UserModel user) {
    }
}
