package ch.anass.keycloak.accessrequests.spi.jpa;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestEventEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestNotificationOutboxEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementAuditEventEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.JitAccessPackageEntity;
import org.junit.jupiter.api.Test;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;

import java.io.InputStream;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AccessRequestJpaEntityProviderTest {

    private static final String FACTORY_ID = "access-requests";
    private static final String CHANGELOG_LOCATION = "META-INF/access-requests-changelog.xml";
    private static final String PROVIDER_CLASS_NAME =
            "ch.anass.keycloak.accessrequests.spi.jpa.AccessRequestJpaEntityProvider";

    @Test
    void discoversTheAccessRequestJpaEntityProviderThroughKeycloakSpi() {
        assertEquals(PROVIDER_CLASS_NAME, providerFactory().getClass().getName());
    }

    @Test
    void usesTheAccessRequestsFactoryId() {
        assertEquals(FACTORY_ID, providerFactory().getId());
    }

    @Test
    void usesTheAccessRequestsFactoryIdForLiquibaseMigrations() {
        assertEquals(FACTORY_ID, providerFactory().create(null).getFactoryId());
    }

    @Test
    void exposesTheAccessRequestEntitiesToKeycloak() {
        JpaEntityProvider provider = providerFactory().create(null);

        assertEquals(7, provider.getEntities().size());
        assertEquals(
                Set.of(
                        AccessRequestEntity.class,
                        AccessGrantEntity.class,
                        AccessRequestEventEntity.class,
                        AccessRequestNotificationOutboxEntity.class,
                        EntitlementEntity.class,
                        EntitlementAuditEventEntity.class,
                        JitAccessPackageEntity.class),
                Set.copyOf(provider.getEntities()));
    }

    @Test
    void pointsKeycloakToAnIncludedLiquibaseChangelog() throws Exception {
        String changelogLocation = providerFactory().create(null).getChangelogLocation();

        assertEquals(CHANGELOG_LOCATION, changelogLocation);
        try (InputStream changelog = getClass().getClassLoader().getResourceAsStream(changelogLocation)) {
            assertNotNull(changelog);
        }
    }

    private JpaEntityProviderFactory providerFactory() {
        return ServiceLoader.load(JpaEntityProviderFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(factory -> PROVIDER_CLASS_NAME.equals(factory.getClass().getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The access request JPA entity provider must be registered through the Keycloak SPI."));
    }
}
