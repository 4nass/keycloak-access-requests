package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementEntity;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaAccessPackageRepositoryTest {

    private static EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaAccessPackageRepository repository;

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
        repository = new JpaAccessPackageRepository(entityManager);
        inTransaction(() -> {
            entityManager.createNativeQuery("delete from AR_ACCESS_PACKAGE_ROLE").executeUpdate();
            entityManager.createNativeQuery("delete from AR_ACCESS_PACKAGE").executeUpdate();
            entityManager.createQuery("delete from EntitlementEntity").executeUpdate();
        });
    }

    @AfterEach
    void closeEntityManager() {
        entityManager.close();
    }

    @Test
    void persistsTheStableGroupBindingAndAllRoleMappingsWithoutCrossRealmReads() {
        persistEntitlement("entitlement-1", "realm-1");
        AccessPackage accessPackage = accessPackage("entitlement-1", "realm-1", "group-1",
                "AR_PKG_entitlement-1");

        inTransaction(() -> repository.create(accessPackage));
        entityManager.clear();

        assertEquals(accessPackage, repository.findByEntitlementId("realm-1", "entitlement-1").orElseThrow());
        assertTrue(repository.findByEntitlementId("other-realm", "entitlement-1").isEmpty());
        assertTrue(repository.findByEntitlementId("realm-1", "missing").isEmpty());
        assertEquals(2L, count("AR_ACCESS_PACKAGE_ROLE"));
    }

    @Test
    void replacesMappingsWithoutChangingTheOwnedGroupOrCrossingRealms() {
        persistEntitlement("entitlement-1", "realm-1");
        AccessPackage original = accessPackage("entitlement-1", "realm-1", "group-1", "AR_PKG_entitlement-1");
        inTransaction(() -> repository.create(original));
        List<AccessPackage.RoleMapping> replacement = List.of(
                new AccessPackage.RoleMapping(ResourceType.CLIENT_ROLE, "another-client-role"));

        inTransaction(() -> repository.replaceRoleMappings("realm-1", "entitlement-1", replacement));
        entityManager.clear();

        AccessPackage saved = repository.findByEntitlementId("realm-1", "entitlement-1").orElseThrow();
        assertEquals(original.groupId(), saved.groupId());
        assertEquals(original.groupName(), saved.groupName());
        assertEquals(replacement, saved.roleMappings());
        assertEquals(1L, count("AR_ACCESS_PACKAGE_ROLE"));
        assertThrows(IllegalArgumentException.class, () -> inTransaction(() -> repository.replaceRoleMappings(
                "other-realm", "entitlement-1", replacement)));
    }

    @Test
    void oneEntitlementCannotBeBoundToTwoGroups() {
        persistEntitlement("entitlement-1", "realm-1");
        inTransaction(() -> repository.create(accessPackage("entitlement-1", "realm-1", "group-1",
                "AR_PKG_entitlement-1")));

        assertThrows(RuntimeException.class, () -> inTransaction(() -> repository.create(
                accessPackage("entitlement-1", "realm-1", "group-2", "AR_PKG_another"))));
        entityManager.clear();
        assertEquals("group-1", repository.findByEntitlementId("realm-1", "entitlement-1")
                .orElseThrow().groupId());
    }

    @Test
    void groupIdCannotBeClaimedByAnotherPackageEvenInAnotherRealm() {
        persistEntitlement("entitlement-1", "realm-1");
        persistEntitlement("entitlement-2", "realm-2");
        inTransaction(() -> repository.create(accessPackage("entitlement-1", "realm-1", "group-1",
                "AR_PKG_entitlement-1")));

        assertThrows(RuntimeException.class, () -> inTransaction(() -> repository.create(
                accessPackage("entitlement-2", "realm-2", "group-1", "AR_PKG_entitlement-2"))));
        entityManager.clear();
        assertTrue(repository.findByEntitlementId("realm-2", "entitlement-2").isEmpty());
        assertEquals(2L, count("AR_ACCESS_PACKAGE_ROLE"));
    }

    @Test
    void groupNameCannotBeReusedInsideTheSameRealm() {
        persistEntitlement("entitlement-1", "realm-1");
        persistEntitlement("entitlement-2", "realm-1");
        inTransaction(() -> repository.create(accessPackage("entitlement-1", "realm-1", "group-1",
                "AR_PKG_shared")));

        assertThrows(RuntimeException.class, () -> inTransaction(() -> repository.create(
                accessPackage("entitlement-2", "realm-1", "group-2", "AR_PKG_shared"))));
        entityManager.clear();
        assertTrue(repository.findByEntitlementId("realm-1", "entitlement-2").isEmpty());
    }

    @Test
    void rejectsBindingForAnUnknownOrDifferentRealmEntitlement() {
        persistEntitlement("entitlement-1", "realm-1");

        assertThrows(RuntimeException.class, () -> inTransaction(() -> repository.create(
                accessPackage("missing", "realm-1", "group-1", "AR_PKG_missing"))));
        assertThrows(RuntimeException.class, () -> inTransaction(() -> repository.create(
                accessPackage("entitlement-1", "realm-2", "group-2", "AR_PKG_wrong_realm"))));
        entityManager.clear();
        assertEquals(0L, count("AR_ACCESS_PACKAGE"));
    }

    @Test
    void aRolledBackBindingLeavesNeitherTheGroupIdNorItsRoleRowsInTheDatabase() {
        persistEntitlement("entitlement-1", "realm-1");

        assertThrows(IllegalStateException.class, () -> inTransaction(() -> {
            repository.create(accessPackage("entitlement-1", "realm-1", "group-1",
                    "AR_PKG_entitlement-1"));
            entityManager.flush();
            throw new IllegalStateException("abort the catalog transaction");
        }));

        entityManager.clear();
        assertTrue(repository.findByEntitlementId("realm-1", "entitlement-1").isEmpty());
        assertEquals(0L, count("AR_ACCESS_PACKAGE"));
        assertEquals(0L, count("AR_ACCESS_PACKAGE_ROLE"));
    }

    private void persistEntitlement(String entitlementId, String realmId) {
        Entitlement entitlement = Entitlement.create(entitlementId, realmId, ResourceType.REALM_ROLE,
                "role-" + entitlementId, "access package", "Temporary access via a package.",
                RiskLevel.LOW, "approver-role", Instant.parse("2026-09-01T10:00:00Z"));
        inTransaction(() -> entityManager.persist(EntitlementEntity.from(entitlement)));
    }

    private static AccessPackage accessPackage(String entitlementId, String realmId, String groupId,
            String groupName) {
        return new AccessPackage(entitlementId, realmId, groupId, groupName, List.of(
                new AccessPackage.RoleMapping(ResourceType.REALM_ROLE, "realm-role"),
                new AccessPackage.RoleMapping(ResourceType.CLIENT_ROLE, "client-role")));
    }

    private long count(String table) {
        return ((Number) entityManager.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue();
    }

    private void inTransaction(Runnable operation) {
        entityManager.getTransaction().begin();
        try {
            operation.run();
            entityManager.getTransaction().commit();
        } catch (RuntimeException exception) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw exception;
        }
    }
}
