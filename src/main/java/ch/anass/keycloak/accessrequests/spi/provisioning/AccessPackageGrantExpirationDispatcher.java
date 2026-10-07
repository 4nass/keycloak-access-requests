package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessGrantRepository;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
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
    private static final int MAX_RETRIES_PER_TICK = 25;

    private final Clock clock;
    private final DueGrantPageReader pages;
    private final RetryGrantPageReader retryPages;
    private final RevocationAttempt revocation;
    private Instant afterExpiry;
    private String afterRequestId;

    public AccessPackageGrantExpirationDispatcher() {
        this(Clock.systemUTC(), AccessPackageGrantExpirationDispatcher::readPage,
                AccessPackageGrantExpirationDispatcher::readRetryPage,
                (factory, realmId, requestId) ->
                        AccessPackageGrantRevocationRunner.scheduled(factory, realmId, requestId));
    }

    AccessPackageGrantExpirationDispatcher(Clock clock, DueGrantPageReader pages, RevocationAttempt revocation) {
        this(clock, pages, (factory, dueAt, limit) -> List.of(), revocation);
    }

    AccessPackageGrantExpirationDispatcher(Clock clock, DueGrantPageReader pages,
            RetryGrantPageReader retryPages, RevocationAttempt revocation) {
        this.clock = Objects.requireNonNull(clock);
        this.pages = Objects.requireNonNull(pages);
        this.retryPages = Objects.requireNonNull(retryPages);
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
        List<AccessGrant> retries = retryPages.read(factory, dueAt, MAX_RETRIES_PER_TICK);
        if (retries.size() > MAX_RETRIES_PER_TICK) {
            throw new IllegalStateException("Retry-grant page exceeded its requested limit");
        }
        int processed = 0;
        for (AccessGrant grant : retries) {
            attempt(factory, grant);
            processed++;
        }
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
                attempt(factory, grant);
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

    private void attempt(KeycloakSessionFactory factory, AccessGrant grant) {
        try {
            revocation.revoke(factory, grant.realmId(), grant.requestId());
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Could not revoke expired package grant %s in realm %s.",
                    grant.requestId(), grant.realmId());
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

    private static List<AccessGrant> readRetryPage(KeycloakSessionFactory factory, Instant dueAt, int limit) {
        return KeycloakModelUtils.runJobInTransactionWithResult(factory, session ->
                new JpaAccessGrantRepository(entityManager(session))
                        .findRetryableFailedPackageGrants(dueAt, limit));
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
    interface RetryGrantPageReader {
        List<AccessGrant> read(KeycloakSessionFactory factory, Instant dueAt, int limit);
    }

    @FunctionalInterface
    interface RevocationAttempt {
        void revoke(KeycloakSessionFactory factory, String realmId, String requestId);
    }
}
