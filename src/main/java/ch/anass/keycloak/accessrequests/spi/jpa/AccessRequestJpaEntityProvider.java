package ch.anass.keycloak.accessrequests.spi.jpa;

import ch.anass.keycloak.accessrequests.persistence.jpa.AccessRequestEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.AccessRequestEventEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.AccessRequestNotificationOutboxEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.EntitlementEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.EntitlementAuditEventEntity;
import ch.anass.keycloak.accessrequests.spi.notification.KeycloakAccessRequestNotificationOutboxDispatcher;
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
            AccessRequestEventEntity.class,
            AccessRequestNotificationOutboxEntity.class,
            EntitlementEntity.class,
            EntitlementAuditEventEntity.class);
    private static final long OUTBOX_INITIAL_DELAY_MILLIS = 1_000;
    private static final long OUTBOX_INTERVAL_MILLIS = 5_000;

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
        KeycloakModelUtils.runJobInTransaction(factory, session -> session.getProvider(TimerProvider.class).scheduleTask(
                new KeycloakAccessRequestNotificationOutboxDispatcher(),
                OUTBOX_INITIAL_DELAY_MILLIS,
                OUTBOX_INTERVAL_MILLIS,
                KeycloakAccessRequestNotificationOutboxDispatcher.TASK_NAME));
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
