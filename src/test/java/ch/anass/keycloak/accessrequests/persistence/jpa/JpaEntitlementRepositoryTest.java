package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementAuditEvent;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementPage;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementQuery;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaEntitlementRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-29T10:15:30Z");

    private static EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaEntitlementRepository repository;

    @BeforeAll
    static void startDatabase() {
        entityManagerFactory = Persistence.createEntityManagerFactory("access-requests-test");
    }

    @AfterAll
    static void stopDatabase() {
        entityManagerFactory.close();
    }

    @BeforeEach
    void openEntityManager() {
        entityManager = entityManagerFactory.createEntityManager();
        repository = new JpaEntitlementRepository(entityManager);
        EntityTransactionSupport.execute(entityManager,
                () -> {
                    entityManager.createQuery("delete from EntitlementAuditEventEntity").executeUpdate();
                    entityManager.createQuery("delete from EntitlementEntity").executeUpdate();
                });
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void returnsOnlyPublishedEntitlementsFromTheRequestedRealm() {
        persist(published("entitlement-1", "realm-1", ResourceType.REALM_ROLE, "role-1", "Finance Reader",
                "Read-only access to finance data.", RiskLevel.LOW));
        persist(unpublished("entitlement-2", "realm-1", ResourceType.GROUP, "group-1", "Finance Group",
                "Membership of the Finance group.", RiskLevel.MEDIUM));
        persist(published("entitlement-3", "realm-2", ResourceType.CLIENT_ROLE, "role-2", "Other Realm",
                "Must not cross the realm boundary.", RiskLevel.HIGH));

        CatalogPage page = repository.findRequestable(new CatalogQuery("realm-1", null, null, null, 0, 20));

        assertEquals(1, page.total());
        assertEquals(List.of("entitlement-1"), page.items().stream().map(Entitlement::id).toList());
        assertTrue(page.items().getFirst().requestable());
    }

    @Test
    void filtersPublishedEntitlementsByTypeRiskAndCaseInsensitiveSearch() {
        persist(published("entitlement-1", "realm-1", ResourceType.CLIENT_ROLE, "role-1", "Finance Reader",
                "Read-only access to finance data.", RiskLevel.LOW));
        persist(published("entitlement-2", "realm-1", ResourceType.GROUP, "group-1", "Accounting Group",
                "Finance department membership.", RiskLevel.HIGH));
        persist(published("entitlement-3", "realm-1", ResourceType.GROUP, "group-2", "Support Group",
                "Support department membership.", RiskLevel.HIGH));

        CatalogPage filtered = repository.findRequestable(new CatalogQuery(
                "realm-1", ResourceType.GROUP, "FINANCE", RiskLevel.HIGH, 0, 20));

        assertEquals(1, filtered.total());
        assertEquals(List.of("entitlement-2"), filtered.items().stream().map(Entitlement::id).toList());
    }

    @Test
    void appliesStablePaginationAndRetainsTheTotalNumberOfMatches() {
        persist(published("entitlement-3", "realm-1", ResourceType.REALM_ROLE, "role-3", "Charlie",
                "Third entitlement.", RiskLevel.LOW));
        persist(published("entitlement-2", "realm-1", ResourceType.REALM_ROLE, "role-2", "Bravo",
                "Second entitlement.", RiskLevel.LOW));
        persist(published("entitlement-1", "realm-1", ResourceType.REALM_ROLE, "role-1", "Alpha",
                "First entitlement.", RiskLevel.LOW));

        CatalogPage firstPage = repository.findRequestable(new CatalogQuery("realm-1", null, null, null, 0, 2));
        CatalogPage secondPage = repository.findRequestable(new CatalogQuery("realm-1", null, null, null, 1, 2));

        assertEquals(3, firstPage.total());
        assertEquals(List.of("Alpha", "Bravo"), firstPage.items().stream().map(Entitlement::displayName).toList());
        assertEquals(List.of("Charlie"), secondPage.items().stream().map(Entitlement::displayName).toList());
        assertEquals(1, secondPage.page());
        assertEquals(2, secondPage.size());
    }

    @Test
    void findsAnEntitlementOnlyWithinItsRealmAndRehydratesAllCatalogFields() {
        persist(published("entitlement-1", "realm-1", ResourceType.CLIENT_ROLE, "role-1", "Finance Reader",
                "Read-only access to finance data.", RiskLevel.CRITICAL));

        Entitlement entitlement = repository.findById("realm-1", "entitlement-1").orElseThrow();

        assertEquals("realm-1", entitlement.realmId());
        assertEquals(ResourceType.CLIENT_ROLE, entitlement.resourceType());
        assertEquals("role-1", entitlement.resourceId());
        assertEquals("Finance Reader", entitlement.displayName());
        assertEquals("Read-only access to finance data.", entitlement.description());
        assertEquals(RiskLevel.CRITICAL, entitlement.riskLevel());
        assertEquals("finance-access-approver", entitlement.approverRoleId());
        assertTrue(entitlement.requestable());
        assertTrue(repository.findById("realm-2", "entitlement-1").isEmpty());
    }

    @Test
    void returnsDraftAndRequestableEntitlementsForAdministrativePagination() {
        persist(published("entitlement-3", "realm-1", ResourceType.REALM_ROLE, "role-3", "Charlie",
                "Third entitlement.", RiskLevel.LOW));
        persist(unpublished("entitlement-2", "realm-1", ResourceType.REALM_ROLE, "role-2", "Bravo",
                "Second entitlement.", RiskLevel.LOW));
        persist(published("entitlement-1", "realm-1", ResourceType.REALM_ROLE, "role-1", "Alpha",
                "First entitlement.", RiskLevel.LOW));
        persist(unpublished("entitlement-4", "realm-2", ResourceType.REALM_ROLE, "role-4", "Other realm",
                "Must not cross the realm boundary.", RiskLevel.LOW));

        EntitlementPage page = repository.findAll(new EntitlementQuery("realm-1", 0, 2));

        assertEquals(3, page.total());
        assertEquals(List.of("Alpha", "Bravo"), page.items().stream().map(Entitlement::displayName).toList());
        assertTrue(page.items().stream().anyMatch(entitlement -> !entitlement.requestable()));
    }

    @Test
    void persistsAnImmutableSnapshotOfTheEntitlementPolicyChange() {
        Entitlement entitlement = published("entitlement-audit", "realm-1", ResourceType.REALM_ROLE, "role-audit",
                "Finance Editor", "Edit access to finance data.", RiskLevel.HIGH).withVersion(1);

        EntityTransactionSupport.execute(entityManager, () -> new JpaEntitlementAuditEventPublisher(entityManager)
                .publish(EntitlementAuditEvent.updated(entitlement, "catalog-manager-1")));

        Object[] event = (Object[]) entityManager.createNativeQuery("""
                        select EVENT_TYPE, ACTOR_ID, REQUESTABLE, VERSION, DISPLAY_NAME
                          from AR_ENTITLEMENT_HISTORY
                         where ENTITLEMENT_ID = 'entitlement-audit'
                        """).getSingleResult();
        assertEquals("ENTITLEMENT_UPDATED", event[0]);
        assertEquals("catalog-manager-1", event[1]);
        assertTrue((Boolean) event[2]);
        assertEquals(1L, ((Number) event[3]).longValue());
        assertEquals("Finance Editor", event[4]);
    }

    private void persist(Entitlement entitlement) {
        EntityTransactionSupport.execute(entityManager, () -> entityManager.persist(EntitlementEntity.from(entitlement)));
    }

    private static Entitlement published(
            String id,
            String realmId,
            ResourceType type,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel) {
        return unpublished(id, realmId, type, resourceId, displayName, description, riskLevel).publish(CREATED_AT);
    }

    private static Entitlement unpublished(
            String id,
            String realmId,
            ResourceType type,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel) {
        return Entitlement.create(
                id,
                realmId,
                type,
                resourceId,
                displayName,
                description,
                riskLevel,
                "finance-access-approver",
                CREATED_AT);
    }

    private static final class EntityTransactionSupport {

        private static void execute(EntityManager entityManager, Runnable operation) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            try {
                operation.run();
                transaction.commit();
            } catch (RuntimeException | Error exception) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw exception;
            }
        }
    }
}
