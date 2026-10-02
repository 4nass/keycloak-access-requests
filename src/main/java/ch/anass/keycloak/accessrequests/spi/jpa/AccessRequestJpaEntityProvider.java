package ch.anass.keycloak.accessrequests.spi.jpa;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.GrantRevocationFailureEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestEventEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestNotificationOutboxEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementAuditEventEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessPackageEntity;
import ch.anass.keycloak.accessrequests.spi.notification.KeycloakAccessRequestNotificationOutboxDispatcher;
import ch.anass.keycloak.accessrequests.spi.provisioning.AccessPackageGrantExpirationDispatcher;
import org.keycloak.Config;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.timer.TimerProvider;

import java.util.List;

public final class AccessRequestJpaEntityProvider implements JpaEntityProvider, JpaEntityProviderFactory {

    private static final String FACTORY_ID = "access-requests";
    private static final String CHANGELOG_LOCATION = "META-INF/access-requests-changelog.xml";
    private static final List<Class<?>> ENTITIES = List.of(
            AccessRequestEntity.class,
            AccessGrantEntity.class,
            GrantRevocationFailureEntity.class,
            AccessRequestEventEntity.class,
            AccessRequestNotificationOutboxEntity.class,
            EntitlementEntity.class,
            EntitlementAuditEventEntity.class,
            AccessPackageEntity.class);
    private static final long OUTBOX_INITIAL_DELAY_MILLIS = 1_000;
    private static final long OUTBOX_INTERVAL_MILLIS = 5_000;
    private static final long GRANT_EXPIRATION_INITIAL_DELAY_MILLIS = 5_000;
    private static final long GRANT_EXPIRATION_INTERVAL_MILLIS = 300_000;

    @Override
    public JpaEntityProvider create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Config.Scope config) {
        // This provider has no configuration.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        KeycloakModelUtils.runJobInTransaction(factory, session -> scheduleTasks(session.getProvider(TimerProvider.class)));
    }

    static void scheduleTasks(TimerProvider timer) {
        timer.scheduleTask(
                new KeycloakAccessRequestNotificationOutboxDispatcher(),
                OUTBOX_INITIAL_DELAY_MILLIS,
                OUTBOX_INTERVAL_MILLIS,
                KeycloakAccessRequestNotificationOutboxDispatcher.TASK_NAME);
        timer.scheduleTask(
                new AccessPackageGrantExpirationDispatcher(),
                GRANT_EXPIRATION_INITIAL_DELAY_MILLIS,
                GRANT_EXPIRATION_INTERVAL_MILLIS,
                AccessPackageGrantExpirationDispatcher.TASK_NAME);
    }

    @Override
    public String getId() {
        return FACTORY_ID;
    }

    @Override
    public List<Class<?>> getEntities() {
        return ENTITIES;
    }

    @Override
    public String getChangelogLocation() {
        return CHANGELOG_LOCATION;
    }

    @Override
    public String getFactoryId() {
        return FACTORY_ID;
    }

    @Override
    public void close() {
        // This provider does not own resources.
    }
}
