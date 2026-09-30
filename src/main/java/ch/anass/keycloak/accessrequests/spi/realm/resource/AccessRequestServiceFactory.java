package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import ch.anass.keycloak.accessrequests.core.service.ApprovalQueueService;
import ch.anass.keycloak.accessrequests.core.service.CatalogService;
import ch.anass.keycloak.accessrequests.core.service.EntitlementScopedApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.service.RequestDetailsService;
import ch.anass.keycloak.accessrequests.core.service.RequestPolicy;
import ch.anass.keycloak.accessrequests.core.service.RequestService;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessGrantRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestHistoryReader;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestNotificationOutboxRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaEntitlementAuditEventPublisher;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaEntitlementRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaJitAccessPackageRepository;
import ch.anass.keycloak.accessrequests.spi.notification.KeycloakAccessRequestNotificationOutboxPublisher;
import ch.anass.keycloak.accessrequests.spi.provisioning.KeycloakEntitlementProvisioner;
import ch.anass.keycloak.accessrequests.spi.provisioning.KeycloakJitPackageGroupFactory;
import ch.anass.keycloak.accessrequests.spi.provisioning.KeycloakJitPackageMembership;
import ch.anass.keycloak.accessrequests.spi.realm.KeycloakAccessRequestTransaction;
import ch.anass.keycloak.accessrequests.spi.realm.KeycloakEffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.spi.realm.KeycloakRoleMembershipReader;
import ch.anass.keycloak.accessrequests.spi.realm.KeycloakUserStatusReader;
import jakarta.persistence.EntityManager;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.List;
import java.util.Objects;

/** Creates request-scoped services with the same Keycloak session and JPA transaction. */
final class AccessRequestServiceFactory {

    private static final RequestPolicy REQUEST_POLICY = new RequestPolicy(10, 2000);
    private final KeycloakSession session;

    AccessRequestServiceFactory(KeycloakSession session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
    }

    KeycloakSession session() {
        return session;
    }

    private EntityManager entityManager() {
        return Objects.requireNonNull(session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null").getEntityManager();
    }

    JpaEntitlementRepository entitlementRepository() {
        return new JpaEntitlementRepository(entityManager());
    }

    JpaJitAccessPackageRepository jitPackageRepository() {
        return new JpaJitAccessPackageRepository(entityManager());
    }

    KeycloakJitPackageGroupFactory jitPackageGroupFactory(RealmModel realm) {
        return new KeycloakJitPackageGroupFactory(session, realm);
    }

    JpaAccessRequestRepository requestRepository() {
        return new JpaAccessRequestRepository(entityManager());
    }

    JpaAccessRequestHistoryReader historyReader() {
        return new JpaAccessRequestHistoryReader(entityManager());
    }

    JpaAccessRequestNotificationOutboxRepository notificationOutboxRepository() {
        return new JpaAccessRequestNotificationOutboxRepository(entityManager());
    }

    JpaEntitlementAuditEventPublisher entitlementAuditEventPublisher() {
        return new JpaEntitlementAuditEventPublisher(entityManager());
    }

    AccessRequestTransaction transaction() {
        return new KeycloakAccessRequestTransaction(session);
    }

    CatalogService catalogService(RealmModel realm, UserModel user) {
        EntityManager entityManager = entityManager();
        return new CatalogService(
                new JpaEntitlementRepository(entityManager),
                new JpaAccessRequestRepository(entityManager),
                new KeycloakEffectiveAccessChecker(session, realm, user,
                        new JpaJitAccessPackageRepository(entityManager)));
    }

    RequestService requestService(RealmModel realm, UserModel user) {
        EntityManager entityManager = entityManager();
        var entitlementRepository = new JpaEntitlementRepository(entityManager);
        var jitPackages = new JpaJitAccessPackageRepository(entityManager);
        return new RequestService(
                entitlementRepository,
                new JpaAccessRequestRepository(entityManager),
                new JpaAccessGrantRepository(entityManager),
                new KeycloakEffectiveAccessChecker(session, realm, user, jitPackages),
                new KeycloakUserStatusReader(realm, user),
                REQUEST_POLICY,
                new JpaAccessRequestEventPublisher(entityManager),
                new EntitlementScopedApprovalAuthorizer(
                        entitlementRepository,
                        new KeycloakRoleMembershipReader(realm, user)),
                transaction(),
                List.of(new KeycloakEntitlementProvisioner(session, realm)),
                new KeycloakAccessRequestNotificationOutboxPublisher(realm, entityManager),
                java.time.Clock.systemUTC(), jitPackages, new KeycloakJitPackageMembership(session, realm));
    }

    ApprovalQueueService approvalQueueService(RealmModel realm, UserModel user) {
        EntityManager entityManager = entityManager();
        return new ApprovalQueueService(
                new JpaAccessRequestRepository(entityManager),
                new JpaEntitlementRepository(entityManager),
                new KeycloakRoleMembershipReader(realm, user));
    }

    RequestDetailsService requestDetailsService() {
        EntityManager entityManager = entityManager();
        return new RequestDetailsService(
                new JpaAccessRequestRepository(entityManager),
                new JpaAccessRequestHistoryReader(entityManager));
    }
}
