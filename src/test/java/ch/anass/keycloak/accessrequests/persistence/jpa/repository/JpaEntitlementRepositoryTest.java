package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementAuditEventEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementEntity;
import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.DurationPolicy;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementAuditEvent;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementPage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementQuery;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void persistsAndUpdatesTheCompleteDurationPolicy() {
        DurationPolicy initial = new DurationPolicy(Duration.ofDays(14), Duration.ofDays(60), true);
        Entitlement created = Entitlement.create("duration-1", "realm-1", ResourceType.REALM_ROLE,
                "role-duration", "Temporary role", "Temporary access.", RiskLevel.LOW,
                "finance-access-approver", initial, CREATED_AT);
        persist(created);

        Entitlement loaded = repository.findById("realm-1", "duration-1").orElseThrow();
        assertEquals(initial, loaded.durationPolicy());

        DurationPolicy replacement = new DurationPolicy(Duration.ofDays(7), Duration.ofDays(21), false);
        Entitlement updated = loaded.updateDetails(loaded.displayName(), loaded.description(),
                loaded.riskLevel(), loaded.approverRoleId(), replacement, CREATED_AT.plusSeconds(1));
        EntityTransactionSupport.execute(entityManager,
                () -> repository.updateIfVersionMatches(updated, loaded.version()).orElseThrow());

        assertEquals(replacement, repository.findById("realm-1", "duration-1").orElseThrow().durationPolicy());
    }

    @Test
    void storesAutoApprovalAsDisabledByDefault() {
        Entitlement draft = unpublished("auto-default", "realm-1", ResourceType.GROUP,
                "package-default", "Default package", "Default approval policy.", RiskLevel.LOW);
        persist(draft);
        entityManager.clear();

        Entitlement restored = repository.findById("realm-1", draft.id()).orElseThrow();
        assertFalse(restored.autoApprove());
        assertEquals(Boolean.FALSE, entityManager.createNativeQuery("""
                        select AUTO_APPROVE from AR_ENTITLEMENT where ID = :id
                        """)
                .setParameter("id", draft.id())
                .getSingleResult());
    }

    @Test
    void rehydratesEnabledAutoApprovalAcrossAllCatalogReadPaths() {
        Entitlement enabled = unpublished("auto-enabled", "realm-1", ResourceType.GROUP,
                "package-enabled", "Automatic package", "Automatically approved low-risk access.", RiskLevel.LOW)
                .withAutoApproval(true, CREATED_AT.plusSeconds(1))
                .publish(CREATED_AT.plusSeconds(1));
        persist(enabled);
        entityManager.clear();

        assertTrue(repository.findById("realm-1", enabled.id()).orElseThrow().autoApprove());
        assertTrue(EntityTransactionSupport.call(entityManager,
                () -> repository.findByIdForUpdate("realm-1", enabled.id()).orElseThrow())
                .autoApprove());
        assertTrue(repository.findAll(new EntitlementQuery("realm-1", 0, 20))
                .items().getFirst().autoApprove());
        assertTrue(repository.findRequestable(new CatalogQuery("realm-1", null, null, null, 0, 20))
                .items().getFirst().autoApprove());
    }

    @Test
    void updatesAutoApprovalWithOptimisticLockingAndPersistsItsRemoval() {
        Entitlement initial = unpublished("auto-update", "realm-1", ResourceType.GROUP,
                "package-update", "Updated package", "Changeable approval policy.", RiskLevel.LOW);
        persist(initial);
        entityManager.clear();

        Entitlement enabled = initial.withAutoApproval(true, CREATED_AT.plusSeconds(1));
        Entitlement updated = EntityTransactionSupport.call(entityManager,
                () -> repository.updateIfVersionMatches(enabled, initial.version()).orElseThrow());
        assertTrue(updated.autoApprove());
        assertEquals(initial.version() + 1, updated.version());

        EntityTransactionSupport.execute(entityManager,
                () -> assertTrue(repository.updateIfVersionMatches(initial, initial.version()).isEmpty()));
        assertTrue(repository.findById("realm-1", initial.id()).orElseThrow().autoApprove());

        Entitlement disabled = updated.withAutoApproval(false, CREATED_AT.plusSeconds(2));
        Entitlement saved = EntityTransactionSupport.call(entityManager,
                () -> repository.updateIfVersionMatches(disabled, updated.version()).orElseThrow());
        entityManager.clear();
        assertFalse(saved.autoApprove());
        assertFalse(repository.findById("realm-1", initial.id()).orElseThrow().autoApprove());
        assertEquals(Boolean.FALSE, entityManager.createNativeQuery("""
                        select AUTO_APPROVE from AR_ENTITLEMENT where ID = :id
                        """)
                .setParameter("id", initial.id())
                .getSingleResult());
    }

    @Test
    void persistsAutomaticApprovalBeingClearedWhenRiskIsRaised() {
        Entitlement enabled = unpublished("auto-risk", "realm-1", ResourceType.GROUP,
                "package-risk", "Risk package", "Risk-sensitive approval policy.", RiskLevel.LOW)
                .withAutoApproval(true, CREATED_AT.plusSeconds(1));
        persist(enabled);
        entityManager.clear();

        Entitlement mediumRisk = enabled.updateDetails(enabled.displayName(), enabled.description(),
                RiskLevel.MEDIUM, enabled.approverRoleId(), enabled.durationPolicy(), CREATED_AT.plusSeconds(2));
        assertFalse(mediumRisk.autoApprove());
        EntityTransactionSupport.execute(entityManager,
                () -> repository.updateIfVersionMatches(mediumRisk, enabled.version()).orElseThrow());
        entityManager.clear();

        Entitlement restored = repository.findById("realm-1", enabled.id()).orElseThrow();
        assertEquals(RiskLevel.MEDIUM, restored.riskLevel());
        assertFalse(restored.autoApprove());
        assertEquals(Boolean.FALSE, entityManager.createNativeQuery("""
                        select AUTO_APPROVE from AR_ENTITLEMENT where ID = :id
                        """)
                .setParameter("id", enabled.id())
                .getSingleResult());
    }

    @Test
    void auditsTheAutoApprovalSettingAsAnImmutablePolicySnapshot() {
        Entitlement enabled = unpublished("auto-audit", "realm-1", ResourceType.GROUP,
                "package-audit", "Audited package", "Audited approval policy.", RiskLevel.LOW)
                .withAutoApproval(true, CREATED_AT.plusSeconds(1));
        Entitlement disabled = enabled.withAutoApproval(false, CREATED_AT.plusSeconds(2)).withVersion(1);

        EntityTransactionSupport.execute(entityManager, () -> {
            JpaEntitlementAuditEventPublisher publisher = new JpaEntitlementAuditEventPublisher(entityManager);
            publisher.publish(EntitlementAuditEvent.created(enabled, "catalog-manager-1"));
            publisher.publish(EntitlementAuditEvent.updated(disabled, "catalog-manager-2"));
        });
        entityManager.clear();

        List<?> snapshots = entityManager.createNativeQuery("""
                        select AUTO_APPROVE from AR_ENTITLEMENT_HISTORY
                         where ENTITLEMENT_ID = :id order by VERSION
                        """)
                .setParameter("id", enabled.id())
                .getResultList();
        assertEquals(List.of(true, false), snapshots);
    }

    @Test
    void persistsRoleChangesWithBothMappingSnapshots() throws Exception {
        Entitlement entitlement = unpublished("role-audit", "realm-1", ResourceType.GROUP,
                "package-role-audit", "Audited package", "A package with editable roles.", RiskLevel.LOW);
        EntityTransactionSupport.execute(entityManager, () -> new JpaEntitlementAuditEventPublisher(entityManager)
                .publish(EntitlementAuditEvent.rolesUpdated(entitlement, "catalog-manager-1",
                        List.of(new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "old-role")),
                        List.of(new AccessPackage.RoleMapping(ResourceType.CLIENT_ROLE, "new-role")))));
        entityManager.clear();

        Object[] snapshots = (Object[]) entityManager.createNativeQuery("""
                        select ROLE_MAPPINGS_BEFORE, ROLE_MAPPINGS_AFTER from AR_ENTITLEMENT_HISTORY
                         where ENTITLEMENT_ID = :id
                        """)
                .setParameter("id", entitlement.id())
                .getSingleResult();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("REALM_ROLE", json.readTree((String) snapshots[0]).get(0).path("type").asText());
        assertEquals("old-role", json.readTree((String) snapshots[0]).get(0).path("roleId").asText());
        assertEquals("CLIENT_ROLE", json.readTree((String) snapshots[1]).get(0).path("type").asText());
        assertEquals("new-role", json.readTree((String) snapshots[1]).get(0).path("roleId").asText());
    }

    @Test
    void persistsTheInitialPackageRoleSelectionOnItsCreationEvent() throws Exception {
        Entitlement entitlement = unpublished("created-role-audit", "realm-1", ResourceType.GROUP,
                "package-created-role-audit", "New package", "Initial roles must be auditable.", RiskLevel.LOW);
        EntityTransactionSupport.execute(entityManager, () -> new JpaEntitlementAuditEventPublisher(entityManager)
                .publish(EntitlementAuditEvent.packageCreated(entitlement, "catalog-manager-1",
                        List.of(new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "initial-role")))));
        entityManager.clear();

        Object[] snapshots = (Object[]) entityManager.createNativeQuery("""
                        select ROLE_MAPPINGS_BEFORE, ROLE_MAPPINGS_AFTER from AR_ENTITLEMENT_HISTORY
                         where ENTITLEMENT_ID = :id and EVENT_TYPE = 'ENTITLEMENT_CREATED'
                        """)
                .setParameter("id", entitlement.id())
                .getSingleResult();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(0, json.readTree((String) snapshots[0]).size());
        assertEquals("REALM_ROLE", json.readTree((String) snapshots[1]).get(0).path("type").asText());
        assertEquals("initial-role", json.readTree((String) snapshots[1]).get(0).path("roleId").asText());
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
    void identifiesOnlyRequestableApproverRolesWithinTheCurrentRealm() {
        persist(Entitlement.create(
                "entitlement-finance", "realm-1", ResourceType.REALM_ROLE, "role-finance", "Finance Reader",
                "Read-only access to finance data.", RiskLevel.LOW, "finance-approver", CREATED_AT).publish(CREATED_AT));
        persist(Entitlement.create(
                "entitlement-draft", "realm-1", ResourceType.REALM_ROLE, "role-draft", "Finance Draft",
                "Draft finance access.", RiskLevel.LOW, "draft-approver", CREATED_AT));
        persist(Entitlement.create(
                "entitlement-other-realm", "realm-2", ResourceType.REALM_ROLE, "role-other", "Other Realm",
                "Must not cross the realm boundary.", RiskLevel.LOW, "other-realm-approver", CREATED_AT).publish(CREATED_AT));

        assertTrue(repository.hasRequestableEntitlementForApproverRoles("realm-1", Set.of("finance-approver")));
        assertFalse(repository.hasRequestableEntitlementForApproverRoles("realm-1", Set.of("draft-approver")));
        assertFalse(repository.hasRequestableEntitlementForApproverRoles("realm-1", Set.of("other-realm-approver")));
        assertFalse(repository.hasRequestableEntitlementForApproverRoles("realm-1", Set.of()));
    }

    @Test
    void persistsAnImmutableSnapshotOfTheEntitlementPolicyChange() {
        Entitlement entitlement = published("entitlement-audit", "realm-1", ResourceType.REALM_ROLE, "role-audit",
                "Finance Editor", "Edit access to finance data.", RiskLevel.HIGH)
                .updateDetails("Finance Editor", "Edit access to finance data.", RiskLevel.HIGH,
                        "finance-access-approver",
                        new DurationPolicy(Duration.ofHours(4), Duration.ofHours(12), true), CREATED_AT)
                .withVersion(1);

        EntityTransactionSupport.execute(entityManager, () -> new JpaEntitlementAuditEventPublisher(entityManager)
                .publish(EntitlementAuditEvent.updated(entitlement, "catalog-manager-1")));

        Object[] event = (Object[]) entityManager.createNativeQuery("""
                        select EVENT_TYPE, ACTOR_ID, REQUESTABLE, VERSION, DISPLAY_NAME,
                               DEFAULT_DURATION_SECONDS, MAX_DURATION_SECONDS, ALLOW_PERMANENT
                          from AR_ENTITLEMENT_HISTORY
                         where ENTITLEMENT_ID = 'entitlement-audit'
                        """).getSingleResult();
        assertEquals("ENTITLEMENT_UPDATED", event[0]);
        assertEquals("catalog-manager-1", event[1]);
        assertTrue((Boolean) event[2]);
        assertEquals(1L, ((Number) event[3]).longValue());
        assertEquals("Finance Editor", event[4]);
        assertEquals(Duration.ofHours(4).toSeconds(), ((Number) event[5]).longValue());
        assertEquals(Duration.ofHours(12).toSeconds(), ((Number) event[6]).longValue());
        assertTrue((Boolean) event[7]);
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

        private static <T> T call(EntityManager entityManager, java.util.function.Supplier<T> operation) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            try {
                T result = operation.get();
                transaction.commit();
                return result;
            } catch (RuntimeException | Error exception) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw exception;
            }
        }
    }
}
