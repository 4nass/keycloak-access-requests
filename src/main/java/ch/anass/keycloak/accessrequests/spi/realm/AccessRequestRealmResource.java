package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.CatalogEntry;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogResult;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.UnauthorizedRequestActionException;
import ch.anass.keycloak.accessrequests.core.service.AccessAlreadyGrantedException;
import ch.anass.keycloak.accessrequests.core.service.CatalogService;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotRequestableException;
import ch.anass.keycloak.accessrequests.core.service.InvalidJustificationException;
import ch.anass.keycloak.accessrequests.core.service.ConcurrentRequestModificationException;
import ch.anass.keycloak.accessrequests.core.service.RequestAlreadyPendingException;
import ch.anass.keycloak.accessrequests.core.service.RequestNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.RequestPolicy;
import ch.anass.keycloak.accessrequests.core.service.RequestService;
import ch.anass.keycloak.accessrequests.core.service.UserDisabledException;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaEntitlementRepository;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.POST;
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
    private static final RequestPolicy REQUEST_POLICY = new RequestPolicy(10, 2000);

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

    @POST
    @Path("requests")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submitRequest(RequestSubmission submission) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        RequestSubmission validatedSubmission = requireSubmission(submission);
        try {
            AccessRequest created = requestService(authenticatedRequest).create(
                    authenticatedRequest.realm().getId(),
                    authenticatedRequest.user().getId(),
                    validatedSubmission.entitlementId(),
                    validatedSubmission.justification());
            return Response.status(Response.Status.CREATED)
                    .entity(RequestResponse.from(created))
                    .build();
        } catch (InvalidJustificationException exception) {
            throw new BadRequestException(exception.getMessage(), exception);
        } catch (EntitlementNotFoundException exception) {
            throw new NotFoundException(exception.getMessage(), exception);
        } catch (EntitlementNotRequestableException | AccessAlreadyGrantedException
                 | RequestAlreadyPendingException exception) {
            throw new ClientErrorException(Response.Status.CONFLICT, exception);
        } catch (UserDisabledException exception) {
            throw new ForbiddenException(exception.getMessage(), exception);
        }
    }

    @GET
    @Path("requests")
    @Produces(MediaType.APPLICATION_JSON)
    public RequestListResponse listRequests(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            AccessRequestPage requestPage = requestService(authenticatedRequest).findByRequester(
                    authenticatedRequest.realm().getId(), authenticatedRequest.user().getId(), page, size);
            return RequestListResponse.from(requestPage);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage(), exception);
        }
    }

    @DELETE
    @Path("requests/{requestId}")
    public Response cancelRequest(@PathParam("requestId") String requestId) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            requestService(authenticatedRequest).cancel(
                    authenticatedRequest.realm().getId(), requestId, authenticatedRequest.user().getId());
            return Response.noContent().build();
        } catch (RequestNotFoundException exception) {
            throw new NotFoundException(exception.getMessage(), exception);
        } catch (UnauthorizedRequestActionException exception) {
            throw new ForbiddenException(exception.getMessage(), exception);
        } catch (InvalidRequestStateException | ConcurrentRequestModificationException exception) {
            throw new ClientErrorException(Response.Status.CONFLICT, exception);
        }
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

    private RequestService requestService(AuthenticatedRequest authenticatedRequest) {
        var entityManager = Objects.requireNonNull(
                session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null")
                .getEntityManager();
        return new RequestService(
                new JpaEntitlementRepository(entityManager),
                new JpaAccessRequestRepository(entityManager),
                new KeycloakEffectiveAccessChecker(
                        session, authenticatedRequest.realm(), authenticatedRequest.user()),
                new KeycloakUserStatusReader(authenticatedRequest.realm(), authenticatedRequest.user()),
                REQUEST_POLICY,
                new JpaAccessRequestEventPublisher(entityManager),
                (realmId, actorId, entitlementId) -> false,
                new KeycloakAccessRequestTransaction(session));
    }

    private static RequestSubmission requireSubmission(RequestSubmission submission) {
        if (submission == null
                || submission.entitlementId() == null
                || submission.entitlementId().isBlank()
                || submission.justification() == null) {
            throw new BadRequestException("entitlementId and justification must be provided");
        }
        return submission;
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

    public record RequestSubmission(String entitlementId, String justification) {
    }

    public record RequestResponse(
            String id,
            String entitlementId,
            DecisionStatus decisionStatus,
            ProvisioningStatus provisioningStatus) {

        private static RequestResponse from(AccessRequest request) {
            return new RequestResponse(
                    request.id(),
                    request.entitlementId(),
                    request.decisionStatus(),
                    request.provisioningStatus());
        }
    }

    public record RequestListResponse(List<RequestSummaryResponse> items, int page, int size, long total) {

        private static RequestListResponse from(AccessRequestPage page) {
            return new RequestListResponse(
                    page.items().stream().map(RequestSummaryResponse::from).toList(),
                    page.page(),
                    page.size(),
                    page.total());
        }
    }

    public record RequestSummaryResponse(
            String id,
            String entitlementId,
            ResourceType resourceType,
            String resourceName,
            DecisionStatus decisionStatus,
            ProvisioningStatus provisioningStatus) {

        private static RequestSummaryResponse from(AccessRequest request) {
            return new RequestSummaryResponse(
                    request.id(),
                    request.entitlementId(),
                    request.resourceType(),
                    request.resourceNameSnapshot(),
                    request.decisionStatus(),
                    request.provisioningStatus());
        }
    }

    private record AuthenticatedRequest(RealmModel realm, UserModel user) {
    }
}
