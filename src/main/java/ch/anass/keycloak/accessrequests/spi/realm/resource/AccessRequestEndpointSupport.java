package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.service.ApprovalQueueService;
import ch.anass.keycloak.accessrequests.core.service.CatalogService;
import ch.anass.keycloak.accessrequests.core.service.RequestDetailsService;
import ch.anass.keycloak.accessrequests.core.service.RequestService;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestHistoryReader;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestNotificationOutboxRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaEntitlementRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaEntitlementAuditEventPublisher;
import ch.anass.keycloak.accessrequests.spi.realm.KeycloakAccessRequestManagerAuthorizer;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApiDto.ErrorResponse;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.AdminRoot;
import org.keycloak.services.resources.admin.fgap.AdminPermissions;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

abstract class AccessRequestEndpointSupport {

    private static final String ACCESS_REQUESTS_API_AUDIENCE = "access-requests-api";
    protected final KeycloakSession session;
    private final AccessRequestServiceFactory services;
    private final KeycloakAccessRequestManagerAuthorizer accessRequestManagerAuthorizer;

    AccessRequestEndpointSupport(AccessRequestServiceFactory services) {
        this.services = Objects.requireNonNull(services, "services must not be null");
        this.session = services.session();
        this.accessRequestManagerAuthorizer = new KeycloakAccessRequestManagerAuthorizer();
    }

    protected AuthenticatedRequest authenticate() {
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

    protected AccessRequestManager requireAccessRequestManager() {
        RealmModel targetRealm = Objects.requireNonNull(session.getContext().getRealm(), "realm must not be null");
        try {
            AdminAuth adminAuth = AdminRoot.authenticateRealmAdminRequest(session);
            var permissions = AdminPermissions.evaluator(session, targetRealm, adminAuth);
            permissions.requireAnyAdminRole();
            if (!permissions.isRealmAdmin()
                    && !accessRequestManagerAuthorizer.canManage(targetRealm, adminAuth.getUser())) {
                throw new ForbiddenException();
            }
            return new AccessRequestManager(targetRealm, adminAuth.getUser(), adminAuth);
        } finally {
            session.getContext().setRealm(targetRealm);
        }
    }

    protected JpaEntitlementRepository entitlementRepository() {
        return services.entitlementRepository();
    }

    protected JpaAccessRequestRepository requestRepository() {
        return services.requestRepository();
    }

    protected JpaAccessRequestHistoryReader historyReader() {
        return services.historyReader();
    }

    protected JpaAccessRequestNotificationOutboxRepository notificationOutboxRepository() {
        return services.notificationOutboxRepository();
    }

    protected JpaEntitlementAuditEventPublisher entitlementAuditEventPublisher() {
        return services.entitlementAuditEventPublisher();
    }

    protected ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction transaction() {
        return services.transaction();
    }
    protected CatalogService catalogService(AuthenticatedRequest authenticatedRequest) {
        return services.catalogService(authenticatedRequest.realm(), authenticatedRequest.user());
    }

    protected RequestService requestService(AuthenticatedRequest authenticatedRequest) {
        return requestService(authenticatedRequest.realm(), authenticatedRequest.user());
    }

    protected RequestService requestService(RealmModel realm, UserModel user) {
        return services.requestService(realm, user);
    }

    protected ApprovalQueueService approvalQueueService(AuthenticatedRequest authenticatedRequest) {
        return services.approvalQueueService(authenticatedRequest.realm(), authenticatedRequest.user());
    }

    protected RequestDetailsService requestDetailsService() {
        return services.requestDetailsService();
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    protected static DecisionStatus parseDecisionStatus(String value) {
        return parseEnum(DecisionStatus.class, value, "status");
    }

    protected static ResourceType parseResourceType(String value) {
        return parseEnum(ResourceType.class, value, "resourceType");
    }

    protected static <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, String parameter) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(parameter + " is invalid", exception);
        }
    }

    protected static Instant parseInstant(String value, String parameter) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(parameter + " must be an ISO-8601 instant", exception);
        }
    }

    protected static Response error(Response.Status status, String code, String message, String requestId) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(code, message, requestId))
                .build();
    }

    protected record AuthenticatedRequest(RealmModel realm, UserModel user) {
    }

    protected record AccessRequestManager(RealmModel realm, UserModel user, AdminAuth auth) {
    }
}
