package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailure;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.service.AccessGrantRevocationService;
import ch.anass.keycloak.accessrequests.core.service.GrantRevocationAuthorityException;
import ch.anass.keycloak.accessrequests.core.service.GrantRevocationRemovalException;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessGrantRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessPackageRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaGrantRevocationFailureRepository;
import ch.anass.keycloak.accessrequests.spi.realm.KeycloakAccessRequestTransaction;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Runs removal and failure recording in separate Keycloak transactions. */
public final class AccessPackageGrantRevocationRunner {

    private static final Logger LOG = Logger.getLogger(AccessPackageGrantRevocationRunner.class);
    private static final String SYSTEM_ACTOR = "access-requests-expiration";

    private AccessPackageGrantRevocationRunner() {
    }

    public static Outcome scheduled(KeycloakSessionFactory factory, String realmId, String requestId) {
        return attempt(factory, realmId, requestId, SYSTEM_ACTOR, Mode.SCHEDULED, null);
    }

    public static Outcome retry(KeycloakSessionFactory factory, String realmId, String requestId, String actorId) {
        boolean open = KeycloakModelUtils.runJobInTransactionWithResult(factory, session ->
                new JpaGrantRevocationFailureRepository(entityManager(session))
                        .findOpen(realmId, requestId).isPresent());
        return open ? attempt(factory, realmId, requestId, actorId, Mode.RETRY, null) : Outcome.notActionable();
    }

    public static Outcome manual(KeycloakSessionFactory factory, String realmId, String requestId,
            String actorId, String reason) {
        return attempt(factory, realmId, requestId, actorId, Mode.MANUAL, reason);
    }

    private static Outcome attempt(KeycloakSessionFactory factory, String realmId, String requestId,
            String actorId, Mode mode, String reason) {
        Objects.requireNonNull(factory);
        try {
            boolean revoked = KeycloakModelUtils.runJobInTransactionWithResult(factory, session -> {
                RealmModel realm = session.realms().getRealm(realmId);
                if (realm == null) {
                    throw new GrantRevocationAuthorityException();
                }
                session.getContext().setRealm(realm);
                EntityManager entityManager = entityManager(session);
                JpaAccessGrantRepository grants = new JpaAccessGrantRepository(entityManager);
                JpaAccessPackageRepository packages = new JpaAccessPackageRepository(entityManager);
                AccessGrantRevocationService revocation = new AccessGrantRevocationService(grants,
                        new AccessPackageGrantAuthority(session, realm, packages),
                        new AccessPackageMembershipRevoker(session, realm, packages),
                        new KeycloakAccessRequestTransaction(session), Clock.systemUTC(),
                        new JpaGrantRevocationFailureRepository(entityManager));
                boolean changed = switch (mode) {
                    case SCHEDULED -> revocation.revokeExpired(realmId, requestId);
                    case MANUAL -> revocation.revokeManually(realmId, requestId);
                    case RETRY -> grants.findByRequestId(realmId, requestId)
                            .filter(grant -> grant.deliveryGroupId() != null).isPresent()
                                    ? revocation.revokeManually(realmId, requestId)
                                    : revocation.revokeExpired(realmId, requestId, false);
                };
                if (changed) {
                    new JpaAccessRequestEventPublisher(entityManager).publish(
                            AccessRequestEvent.revocationSucceeded(requestId, realmId, actorId, Instant.now(), reason));
                }
                return changed;
            });
            return revoked ? Outcome.revoked() : Outcome.notActionable();
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Could not revoke package grant %s in realm %s.", requestId, realmId);
            GrantRevocationFailureCode code = failureCode(exception);
            GrantRevocationFailure failure = KeycloakModelUtils.runJobInTransactionWithResult(factory, session -> {
                EntityManager entityManager = entityManager(session);
                Instant now = Instant.now();
                GrantRevocationFailure recorded = new JpaGrantRevocationFailureRepository(entityManager)
                        .record(realmId, requestId, code, now).orElse(null);
                if (recorded != null) {
                    new JpaAccessRequestEventPublisher(entityManager).publish(
                            AccessRequestEvent.revocationFailed(requestId, realmId, actorId, now, code,
                                    recorded.attemptCount()));
                }
                return recorded;
            });
            return failure == null ? Outcome.notActionable() : Outcome.failed(code);
        }
    }

    private static GrantRevocationFailureCode failureCode(RuntimeException exception) {
        if (exception instanceof GrantRevocationAuthorityException) {
            return GrantRevocationFailureCode.AUTHORITY_UNVERIFIABLE;
        }
        if (exception instanceof GrantRevocationRemovalException) {
            return GrantRevocationFailureCode.REMOVAL_FAILED;
        }
        return GrantRevocationFailureCode.UNEXPECTED_FAILURE;
    }

    private static EntityManager entityManager(KeycloakSession session) {
        return Objects.requireNonNull(session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null").getEntityManager();
    }

    private enum Mode { SCHEDULED, RETRY, MANUAL }

    public record Outcome(Status status, GrantRevocationFailureCode failureCode) {
        public enum Status { REVOKED, FAILED, NOT_ACTIONABLE }

        static Outcome revoked() {
            return new Outcome(Status.REVOKED, null);
        }

        static Outcome failed(GrantRevocationFailureCode code) {
            return new Outcome(Status.FAILED, code);
        }

        static Outcome notActionable() {
            return new Outcome(Status.NOT_ACTIONABLE, null);
        }
    }
}
