package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.service.AccessGrantRevocationService;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessGrantRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessPackageRepository;
import ch.anass.keycloak.accessrequests.spi.realm.KeycloakAccessRequestTransaction;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.timer.ScheduledTask;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Scans due package grants and delegates each revocation to a separate Keycloak transaction. */
public final class AccessPackageGrantExpirationDispatcher implements ScheduledTask {

    public static final String TASK_NAME = "access-requests-package-grant-expiration";
    private static final Logger LOG = Logger.getLogger(AccessPackageGrantExpirationDispatcher.class);
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PER_TICK = 100;

    private final Clock clock;
    private final DueGrantPageReader pages;
    private final RevocationAttempt revocation;
    private Instant afterExpiry;
    private String afterRequestId;

    public AccessPackageGrantExpirationDispatcher() {
        this(Clock.systemUTC(), AccessPackageGrantExpirationDispatcher::readPage,
                AccessPackageGrantExpirationDispatcher::revoke);
    }

    AccessPackageGrantExpirationDispatcher(Clock clock, DueGrantPageReader pages, RevocationAttempt revocation) {
        this.clock = Objects.requireNonNull(clock);
        this.pages = Objects.requireNonNull(pages);
        this.revocation = Objects.requireNonNull(revocation);
    }

    @Override
    public String getTaskName() {
        return TASK_NAME;
    }

    @Override
    public synchronized void run(KeycloakSession session) {
        KeycloakSessionFactory factory = Objects.requireNonNull(session, "session must not be null")
                .getKeycloakSessionFactory();
        Instant dueAt = Instant.now(clock);
        int processed = 0;
        while (processed < MAX_PER_TICK) {
            int limit = Math.min(PAGE_SIZE, MAX_PER_TICK - processed);
            List<AccessGrant> candidates = pages.read(factory, dueAt, afterExpiry, afterRequestId, limit);
            if (candidates.isEmpty()) {
                resetCursor();
                return;
            }
            if (candidates.size() > limit) {
                throw new IllegalStateException("Due-grant page exceeded its requested limit");
            }
            for (AccessGrant grant : candidates) {
                try {
                    revocation.revoke(factory, grant.realmId(), grant.requestId());
                } catch (RuntimeException exception) {
                    LOG.warnf(exception, "Could not revoke expired package grant %s in realm %s.",
                            grant.requestId(), grant.realmId());
                }
                afterExpiry = grant.expiresAt();
                afterRequestId = grant.requestId();
                processed++;
            }
            if (candidates.size() < limit) {
                resetCursor();
                return;
            }
        }
    }

    private void resetCursor() {
        afterExpiry = null;
        afterRequestId = null;
    }

    private static List<AccessGrant> readPage(KeycloakSessionFactory factory, Instant dueAt,
            Instant afterExpiry, String afterRequestId, int limit) {
        return KeycloakModelUtils.runJobInTransactionWithResult(factory, session ->
                new JpaAccessGrantRepository(entityManager(session))
                        .findDuePackageGrants(dueAt, afterExpiry, afterRequestId, limit));
    }

    private static void revoke(KeycloakSessionFactory factory, String realmId, String requestId) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            RealmModel realm = session.realms().getRealm(realmId);
            if (realm == null) {
                throw new IllegalStateException("Expired package grant realm is unavailable");
            }
            session.getContext().setRealm(realm);
            EntityManager entityManager = entityManager(session);
            JpaAccessGrantRepository grants = new JpaAccessGrantRepository(entityManager);
            JpaAccessPackageRepository packages = new JpaAccessPackageRepository(entityManager);
            new AccessGrantRevocationService(grants,
                    new AccessPackageGrantAuthority(session, realm, packages),
                    new AccessPackageMembershipRevoker(session, realm, packages),
                    new KeycloakAccessRequestTransaction(session), Clock.systemUTC())
                    .revokeExpired(realmId, requestId);
        });
    }

    private static EntityManager entityManager(KeycloakSession session) {
        return Objects.requireNonNull(session.getProvider(JpaConnectionProvider.class),
                "Keycloak JPA connection provider must not be null").getEntityManager();
    }

    @FunctionalInterface
    interface DueGrantPageReader {
        List<AccessGrant> read(KeycloakSessionFactory factory, Instant dueAt, Instant afterExpiry,
                String afterRequestId, int limit);
    }

    @FunctionalInterface
    interface RevocationAttempt {
        void revoke(KeycloakSessionFactory factory, String realmId, String requestId);
    }
}
