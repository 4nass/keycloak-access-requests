package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.CatalogEntry;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogResult;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueEntry;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.SelfApprovalException;
import ch.anass.keycloak.accessrequests.core.domain.UnauthorizedApprovalException;
import ch.anass.keycloak.accessrequests.core.domain.UnauthorizedRequestActionException;
import ch.anass.keycloak.accessrequests.core.service.AccessAlreadyGrantedException;
import ch.anass.keycloak.accessrequests.core.service.ApprovalQueueService;
import ch.anass.keycloak.accessrequests.core.service.CatalogService;
import ch.anass.keycloak.accessrequests.core.service.EntitlementScopedApprovalAuthorizer;
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
import ch.anass.keycloak.accessrequests.spi.provisioning.KeycloakEntitlementProvisioner;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
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

import java.time.Instant;
import java.time.format.DateTimeParseException;
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
    @Path("mine")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listRequests(
            @QueryParam("status") String decisionStatus,
            @QueryParam("resourceType") String resourceType,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            AccessRequestPage requestPage = requestService(authenticatedRequest).findByRequester(
                    new AccessRequestQuery(
                            authenticatedRequest.realm().getId(),
                            authenticatedRequest.user().getId(),
                            parseDecisionStatus(decisionStatus),
                            parseResourceType(resourceType),
                            parseInstant(from, "from"),
                            parseInstant(to, "to"),
                            page,
                            size));
            return Response.ok(RequestListResponse.from(requestPage)).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST_QUERY", exception.getMessage(), null);
        }
    }

    @GET
    @Path("pending")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listPendingRequests(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            ApprovalQueuePage requestPage = approvalQueueService(authenticatedRequest).findPending(
                    authenticatedRequest.realm().getId(),
                    authenticatedRequest.user().getId(),
                    page,
                    size);
            return Response.ok(PendingRequestListResponse.from(requestPage)).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST_QUERY", exception.getMessage(), null);
        }
    }

    @POST
    @Path("{requestId}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelRequest(@PathParam("requestId") String requestId) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            requestService(authenticatedRequest).cancel(
                    authenticatedRequest.realm().getId(), requestId, authenticatedRequest.user().getId());
            return Response.noContent().build();
        } catch (RequestNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "REQUEST_NOT_FOUND", exception.getMessage(), requestId);
        } catch (UnauthorizedRequestActionException exception) {
            return error(Response.Status.FORBIDDEN, "REQUEST_CANCELLATION_FORBIDDEN", exception.getMessage(), requestId);
        } catch (InvalidRequestStateException exception) {
            return error(Response.Status.CONFLICT, "INVALID_REQUEST_STATE", exception.getMessage(), requestId);
        } catch (ConcurrentRequestModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_MODIFICATION", exception.getMessage(), requestId);
        }
    }

    @POST
    @Path("{requestId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response approveRequest(
            @PathParam("requestId") String requestId,
            DecisionSubmission submission) {
        return decide(requestId, submission, true);
    }

    @POST
    @Path("{requestId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rejectRequest(
            @PathParam("requestId") String requestId,
            DecisionSubmission submission) {
        return decide(requestId, submission, false);
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
        var entitlementRepository = new JpaEntitlementRepository(entityManager);
        return new RequestService(
                entitlementRepository,
                new JpaAccessRequestRepository(entityManager),
                new KeycloakEffectiveAccessChecker(
                        session, authenticatedRequest.realm(), authenticatedRequest.user()),
                new KeycloakUserStatusReader(authenticatedRequest.realm(), authenticatedRequest.user()),
                REQUEST_POLICY,
                new JpaAccessRequestEventPublisher(entityManager),
                new EntitlementScopedApprovalAuthorizer(
                        entitlementRepository,
                        new KeycloakRoleMembershipReader(
                                authenticatedRequest.realm(), authenticatedRequest.user())),
                new KeycloakAccessRequestTransaction(session),
                List.of(new KeycloakEntitlementProvisioner(session, authenticatedRequest.realm())));
    }

    private ApprovalQueueService approvalQueueService(AuthenticatedRequest authenticatedRequest) {
        var entityManager = Objects.requireNonNull(
                session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null")
                .getEntityManager();
        return new ApprovalQueueService(
                new JpaAccessRequestRepository(entityManager),
                new KeycloakRoleMembershipReader(
                        authenticatedRequest.realm(), authenticatedRequest.user()));
    }

    private Response decide(String requestId, DecisionSubmission submission, boolean approved) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        if (submission == null) {
            return error(
                    Response.Status.BAD_REQUEST,
                    "INVALID_DECISION_SUBMISSION",
                    "A decision payload must be provided",
                    requestId);
        }
        try {
            RequestService requestService = requestService(authenticatedRequest);
            AccessRequest decided = approved
                    ? requestService.approve(
                            authenticatedRequest.realm().getId(),
                            requestId,
                            authenticatedRequest.user().getId(),
                            submission.comment())
                    : requestService.reject(
                            authenticatedRequest.realm().getId(),
                            requestId,
                            authenticatedRequest.user().getId(),
                            submission.comment());
            return Response.ok(RequestResponse.from(decided)).build();
        } catch (RequestNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "REQUEST_NOT_FOUND", exception.getMessage(), requestId);
        } catch (SelfApprovalException exception) {
            return error(Response.Status.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN", exception.getMessage(), requestId);
        } catch (UnauthorizedApprovalException exception) {
            return error(Response.Status.FORBIDDEN, "NOT_AUTHORIZED_APPROVER", exception.getMessage(), requestId);
        } catch (EntitlementNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "ENTITLEMENT_NOT_FOUND", exception.getMessage(), requestId);
        } catch (EntitlementNotRequestableException exception) {
            return error(Response.Status.CONFLICT, "ENTITLEMENT_NOT_REQUESTABLE", exception.getMessage(), requestId);
        } catch (InvalidRequestStateException exception) {
            return error(Response.Status.CONFLICT, "INVALID_REQUEST_STATE", exception.getMessage(), requestId);
        } catch (ConcurrentRequestModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_MODIFICATION", exception.getMessage(), requestId);
        }
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

    private static DecisionStatus parseDecisionStatus(String value) {
        return parseEnum(DecisionStatus.class, value, "status");
    }

    private static ResourceType parseResourceType(String value) {
        return parseEnum(ResourceType.class, value, "resourceType");
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, String parameter) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(parameter + " is invalid", exception);
        }
    }

    private static Instant parseInstant(String value, String parameter) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(parameter + " must be an ISO-8601 instant", exception);
        }
    }

    private static Response error(Response.Status status, String code, String message, String requestId) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(code, message, requestId))
                .build();
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

    public record DecisionSubmission(String comment) {
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
            ProvisioningStatus provisioningStatus,
            String createdAt) {

        private static RequestSummaryResponse from(AccessRequest request) {
            return new RequestSummaryResponse(
                    request.id(),
                    request.entitlementId(),
                    request.resourceType(),
                    request.resourceNameSnapshot(),
                    request.decisionStatus(),
                    request.provisioningStatus(),
                    request.createdAt().toString());
        }
    }

    public record PendingRequestListResponse(List<PendingRequestSummaryResponse> items, int page, int size, long total) {

        private static PendingRequestListResponse from(ApprovalQueuePage page) {
            return new PendingRequestListResponse(
                    page.items().stream().map(PendingRequestSummaryResponse::from).toList(),
                    page.page(),
                    page.size(),
                    page.total());
        }
    }

    public record PendingRequestSummaryResponse(
            String id,
            String requesterId,
            String entitlementId,
            ResourceType resourceType,
            String resourceName,
            RiskLevel riskLevel,
            String justification,
            String createdAt) {

        private static PendingRequestSummaryResponse from(ApprovalQueueEntry entry) {
            AccessRequest request = entry.request();
            return new PendingRequestSummaryResponse(
                    request.id(),
                    request.requesterId(),
                    request.entitlementId(),
                    request.resourceType(),
                    request.resourceNameSnapshot(),
                    entry.riskLevel(),
                    request.justification(),
                    request.createdAt().toString());
        }
    }

    public record ErrorResponse(String code, String message, String requestId) {
    }

    private record AuthenticatedRequest(RealmModel realm, UserModel user) {
    }
}
