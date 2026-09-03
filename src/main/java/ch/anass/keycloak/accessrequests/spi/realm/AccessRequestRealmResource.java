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
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementAuditEvent;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementPage;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementQuery;
import ch.anass.keycloak.accessrequests.core.domain.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.SelfApprovalException;
import ch.anass.keycloak.accessrequests.core.domain.UnauthorizedApprovalException;
import ch.anass.keycloak.accessrequests.core.domain.UnauthorizedRequestActionException;
import ch.anass.keycloak.accessrequests.core.port.DuplicateEntitlementException;
import ch.anass.keycloak.accessrequests.core.service.AccessAlreadyGrantedException;
import ch.anass.keycloak.accessrequests.core.service.ApprovalQueueService;
import ch.anass.keycloak.accessrequests.core.service.CatalogService;
import ch.anass.keycloak.accessrequests.core.service.EntitlementScopedApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotRequestableException;
import ch.anass.keycloak.accessrequests.core.service.InvalidJustificationException;
import ch.anass.keycloak.accessrequests.core.service.ConcurrentRequestModificationException;
import ch.anass.keycloak.accessrequests.core.service.ConcurrentEntitlementModificationException;
import ch.anass.keycloak.accessrequests.core.service.RequestAlreadyPendingException;
import ch.anass.keycloak.accessrequests.core.service.RequestNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.RequestPolicy;
import ch.anass.keycloak.accessrequests.core.service.RequestService;
import ch.anass.keycloak.accessrequests.core.service.UserDisabledException;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaEntitlementRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.JpaEntitlementAuditEventPublisher;
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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.AdminRoot;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AccessRequestRealmResource {

    private static final String ACCESS_REQUESTS_API_AUDIENCE = "access-requests-api";
    private static final RequestPolicy REQUEST_POLICY = new RequestPolicy(10, 2000);

    private final KeycloakSession session;
    private final KeycloakAccessRequestManagerAuthorizer accessRequestManagerAuthorizer;

    AccessRequestRealmResource(KeycloakSession session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.accessRequestManagerAuthorizer = new KeycloakAccessRequestManagerAuthorizer();
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

    @GET
    @Path("admin/entitlements")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCatalogEntitlements(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        AccessRequestManager manager = requireAccessRequestManager();
        try {
            EntitlementPage entitlementPage = entitlementRepository().findAll(
                    new EntitlementQuery(manager.realm().getId(), page, size));
            return Response.ok(EntitlementListResponse.from(entitlementPage)).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_ENTITLEMENT_QUERY", exception.getMessage(), null);
        }
    }

    @POST
    @Path("admin/entitlements")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createEntitlement(EntitlementCreation submission) {
        AccessRequestManager manager = requireAccessRequestManager();
        EntitlementCreation validatedSubmission = requireEntitlementCreation(submission);
        validateKeycloakReferences(manager.realm(), validatedSubmission);
        Entitlement created = Entitlement.create(
                UUID.randomUUID().toString(),
                manager.realm().getId(),
                validatedSubmission.resourceType(),
                validatedSubmission.resourceId(),
                validatedSubmission.displayName(),
                validatedSubmission.description(),
                validatedSubmission.riskLevel(),
                validatedSubmission.approverRoleId(),
                Instant.now());
        try {
            Entitlement persisted = transaction().execute(() -> {
                Entitlement createdEntitlement = entitlementRepository().create(created);
                entitlementAuditEventPublisher().publish(
                        EntitlementAuditEvent.created(createdEntitlement, manager.user().getId()));
                return createdEntitlement;
            });
            return Response.status(Response.Status.CREATED).entity(EntitlementResponse.from(persisted)).build();
        } catch (DuplicateEntitlementException exception) {
            return error(
                    Response.Status.CONFLICT,
                    "ENTITLEMENT_ALREADY_EXISTS",
                    exception.getMessage(),
                    null);
        }
    }

    @GET
    @Path("admin/entitlements/{entitlementId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEntitlement(@PathParam("entitlementId") String entitlementId) {
        AccessRequestManager manager = requireAccessRequestManager();
        return Response.ok(EntitlementResponse.from(findEntitlement(manager.realm(), entitlementId))).build();
    }

    @PUT
    @Path("admin/entitlements/{entitlementId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateEntitlement(
            @PathParam("entitlementId") String entitlementId,
            EntitlementUpdate submission) {
        AccessRequestManager manager = requireAccessRequestManager();
        EntitlementUpdate validatedSubmission = requireEntitlementUpdate(submission);
        validateApproverRole(manager.realm(), validatedSubmission.approverRoleId());
        Entitlement current = findEntitlement(manager.realm(), entitlementId);
        try {
            Instant updatedAt = Instant.now();
            Entitlement updated = current.updateDetails(
                    validatedSubmission.displayName(),
                    validatedSubmission.description(),
                    validatedSubmission.riskLevel(),
                    validatedSubmission.approverRoleId(),
                    updatedAt);
            updated = validatedSubmission.requestable()
                    ? updated.publish(updatedAt)
                    : updated.unpublish(updatedAt);
            return Response.ok(EntitlementResponse.from(
                    persistEntitlementUpdate(updated, validatedSubmission.version(), manager.user().getId()))).build();
        } catch (ConcurrentEntitlementModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_ENTITLEMENT_MODIFICATION", exception.getMessage(), null);
        }
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

    @GET
    @Path("capabilities")
    @Produces(MediaType.APPLICATION_JSON)
    public CapabilitiesResponse capabilities() {
        AuthenticatedRequest authenticatedRequest = authenticate();
        return new CapabilitiesResponse(approvalQueueService(authenticatedRequest).canApprove(
                authenticatedRequest.realm().getId(), authenticatedRequest.user().getId()));
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
                .setHeaders(session.getContext().getHttpRequest().getHttpHeaders())
                .setAudience(ACCESS_REQUESTS_API_AUDIENCE)
                .authenticate();
        if (authentication == null || authentication.user() == null) {
            throw new NotAuthorizedException("Bearer");
        }
        return new AuthenticatedRequest(realm, authentication.user());
    }

    private AccessRequestManager requireAccessRequestManager() {
        RealmModel targetRealm = Objects.requireNonNull(session.getContext().getRealm(), "realm must not be null");
        try {
            AdminAuth adminAuth = AdminRoot.authenticateRealmAdminRequest(session);
            if (!accessRequestManagerAuthorizer.canManage(targetRealm, adminAuth.getUser())) {
                throw new ForbiddenException();
            }
            return new AccessRequestManager(targetRealm, adminAuth.getUser());
        } finally {
            session.getContext().setRealm(targetRealm);
        }
    }

    private JpaEntitlementRepository entitlementRepository() {
        var entityManager = Objects.requireNonNull(
                session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null")
                .getEntityManager();
        return new JpaEntitlementRepository(entityManager);
    }

    private JpaEntitlementAuditEventPublisher entitlementAuditEventPublisher() {
        var entityManager = Objects.requireNonNull(
                session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null")
                .getEntityManager();
        return new JpaEntitlementAuditEventPublisher(entityManager);
    }

    private KeycloakAccessRequestTransaction transaction() {
        return new KeycloakAccessRequestTransaction(session);
    }

    private static EntitlementCreation requireEntitlementCreation(EntitlementCreation submission) {
        if (submission == null
                || submission.resourceType() == null
                || isBlank(submission.resourceId())
                || isBlank(submission.displayName())
                || isBlank(submission.description())
                || submission.riskLevel() == null
                || isBlank(submission.approverRoleId())) {
            throw new BadRequestException(
                    "resourceType, resourceId, displayName, description, riskLevel, and approverRoleId must be provided");
        }
        return submission;
    }

    private static EntitlementUpdate requireEntitlementUpdate(EntitlementUpdate submission) {
        if (submission == null
                || isBlank(submission.displayName())
                || isBlank(submission.description())
                || submission.riskLevel() == null
                || isBlank(submission.approverRoleId())
                || submission.requestable() == null
                || submission.version() == null
                || submission.version() < 0) {
            throw new BadRequestException(
                    "displayName, description, riskLevel, approverRoleId, requestable, and a non-negative version must be provided");
        }
        return submission;
    }

    private void validateKeycloakReferences(RealmModel realm, EntitlementCreation submission) {
        switch (submission.resourceType()) {
            case REALM_ROLE -> requireRole(realm, submission.resourceId(), false, "resourceId");
            case CLIENT_ROLE -> requireRole(realm, submission.resourceId(), true, "resourceId");
            case GROUP -> requireGroup(realm, submission.resourceId());
        }
        validateApproverRole(realm, submission.approverRoleId());
    }

    private static void validateApproverRole(RealmModel realm, String approverRoleId) {
        requireRole(realm, approverRoleId, false, "approverRoleId");
    }

    private static RoleModel requireRole(RealmModel realm, String roleId, boolean clientRole, String fieldName) {
        RoleModel role = realm.getRoleById(roleId);
        if (role == null || role.isClientRole() != clientRole) {
            throw new BadRequestException(fieldName + " must reference an existing "
                    + (clientRole ? "client" : "realm") + " role");
        }
        return role;
    }

    private void requireGroup(RealmModel realm, String groupId) {
        GroupModel group = session.groups().getGroupById(realm, groupId);
        if (group == null) {
            throw new BadRequestException("resourceId must reference an existing group");
        }
    }

    private Entitlement findEntitlement(RealmModel realm, String entitlementId) {
        return entitlementRepository().findById(realm.getId(), entitlementId)
                .orElseThrow(() -> new NotFoundException("Entitlement not found: " + entitlementId));
    }

    private Entitlement persistEntitlementUpdate(Entitlement entitlement, long expectedVersion, String actorId) {
        return transaction().execute(() -> {
            Entitlement persisted = entitlementRepository()
                    .updateIfVersionMatches(entitlement, expectedVersion)
                    .orElseThrow(() -> new ConcurrentEntitlementModificationException(entitlement.id()));
            entitlementAuditEventPublisher().publish(EntitlementAuditEvent.updated(persisted, actorId));
            return persisted;
        });
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
                new JpaEntitlementRepository(entityManager),
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    public record EntitlementCreation(
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId) {
    }

    public record EntitlementUpdate(
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            Boolean requestable,
            Long version) {
    }

    public record EntitlementResponse(
            String id,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            boolean requestable,
            String createdAt,
            String updatedAt,
            long version) {

        private static EntitlementResponse from(Entitlement entitlement) {
            return new EntitlementResponse(
                    entitlement.id(),
                    entitlement.resourceType(),
                    entitlement.resourceId(),
                    entitlement.displayName(),
                    entitlement.description(),
                    entitlement.riskLevel(),
                    entitlement.approverRoleId(),
                    entitlement.requestable(),
                    entitlement.createdAt().toString(),
                    entitlement.updatedAt().toString(),
                    entitlement.version());
        }
    }

    public record EntitlementListResponse(List<EntitlementResponse> items, int page, int size, long total) {

        private static EntitlementListResponse from(EntitlementPage page) {
            return new EntitlementListResponse(
                    page.items().stream().map(EntitlementResponse::from).toList(),
                    page.page(),
                    page.size(),
                    page.total());
        }
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

    public record CapabilitiesResponse(boolean canApprove) {
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

    private record AccessRequestManager(RealmModel realm, UserModel user) {
    }
}
