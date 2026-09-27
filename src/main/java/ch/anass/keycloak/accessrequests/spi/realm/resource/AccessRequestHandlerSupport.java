package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
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
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.AdminRoot;
import org.keycloak.services.resources.admin.fgap.AdminPermissions;

import java.util.Objects;

abstract class AccessRequestHandlerSupport {

    private static final String ACCESS_REQUESTS_API_AUDIENCE = "access-requests-api";
    protected final KeycloakSession session;
    private final AccessRequestServiceFactory services;
    private final KeycloakAccessRequestManagerAuthorizer accessRequestManagerAuthorizer;

    AccessRequestHandlerSupport(AccessRequestServiceFactory services) {
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

    protected AccessRequestTransaction transaction() {
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

    protected record AuthenticatedRequest(RealmModel realm, UserModel user) {
    }

    protected record AccessRequestManager(RealmModel realm, UserModel user, AdminAuth auth) {
    }
}
