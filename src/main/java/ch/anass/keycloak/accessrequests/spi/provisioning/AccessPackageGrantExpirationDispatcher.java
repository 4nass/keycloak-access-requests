package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.timer.ScheduledTask;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Timer entry point for package-grant expiry; the sweep is specified before implementation. */
public final class AccessPackageGrantExpirationDispatcher implements ScheduledTask {

    public static final String TASK_NAME = "access-requests-package-grant-expiration";

    private final Clock clock;
    private final DueGrantPageReader pages;
    private final RevocationAttempt revocation;

    public AccessPackageGrantExpirationDispatcher() {
        this(Clock.systemUTC(), (factory, dueAt, afterExpiry, afterRequestId, limit) -> {
            throw new UnsupportedOperationException("Package-grant expiry is not implemented");
        }, (factory, realmId, requestId) -> {
            throw new UnsupportedOperationException("Package-grant revocation is not implemented");
        });
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
    public void run(KeycloakSession session) {
        throw new UnsupportedOperationException("Package-grant expiry sweep is not implemented");
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
