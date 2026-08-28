package ch.anass.keycloak.accessrequests.spi.jpa;

import ch.anass.keycloak.accessrequests.persistence.jpa.AccessRequestEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.AccessRequestEventEntity;
import org.keycloak.Config;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.List;

public final class AccessRequestJpaEntityProvider implements JpaEntityProvider, JpaEntityProviderFactory {

    private static final String FACTORY_ID = "access-requests";
    private static final String CHANGELOG_LOCATION = "META-INF/access-requests-changelog.xml";
    private static final List<Class<?>> ENTITIES = List.of(AccessRequestEntity.class, AccessRequestEventEntity.class);

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
        // This provider has no post-initialization work.
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
