package ch.anass.keycloak.accessrequests.core.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementCatalogLifecycleTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-29T10:15:30Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-29T10:16:30Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-29T10:17:30Z");
    private static final Instant UNPUBLISHED_AT = Instant.parse("2026-08-29T10:18:30Z");

    @Test
    void newEntitlementStartsUnpublished() {
        Entitlement entitlement = unpublishedEntitlement();

        assertFalse(entitlement.requestable());
        assertEquals(CREATED_AT, entitlement.createdAt());
        assertEquals(CREATED_AT, entitlement.updatedAt());
        assertEquals(0, entitlement.version());
    }

    @Test
    void newEntitlementPreservesItsCatalogMetadata() {
        Entitlement entitlement = unpublishedEntitlement();

        assertEquals("entitlement-1", entitlement.id());
        assertEquals("realm-1", entitlement.realmId());
        assertEquals(ResourceType.CLIENT_ROLE, entitlement.resourceType());
        assertEquals("role-1", entitlement.resourceId());
        assertEquals("Finance Reader", entitlement.displayName());
        assertEquals("Read-only access to the Finance Portal.", entitlement.description());
        assertEquals(RiskLevel.LOW, entitlement.riskLevel());
        assertEquals("finance-access-approver", entitlement.approverRoleId());
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void catalogSupportsEveryV0ResourceType(ResourceType resourceType) {
        Entitlement entitlement = Entitlement.create(
                "entitlement-1",
                "realm-1",
                resourceType,
                "resource-1",
                "Resource",
                "A requestable Keycloak resource.",
                RiskLevel.MEDIUM,
                "access-request-approver",
                CREATED_AT);

        assertEquals(resourceType, entitlement.resourceType());
    }

    @ParameterizedTest
    @EnumSource(RiskLevel.class)
    void catalogSupportsEveryV0RiskLevel(RiskLevel riskLevel) {
        Entitlement entitlement = Entitlement.create(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                riskLevel,
                "finance-access-approver",
                CREATED_AT);

        assertEquals(riskLevel, entitlement.riskLevel());
    }

    @Test
    void unpublishedEntitlementCanBePublished() {
        Entitlement entitlement = unpublishedEntitlement();

        Entitlement published = entitlement.publish(PUBLISHED_AT);

        assertTrue(published.requestable());
        assertEquals(PUBLISHED_AT, published.updatedAt());
        assertEquals(CREATED_AT, published.createdAt());
        assertEquals(0, published.version());
        assertFalse(entitlement.requestable());
        assertEquals(CREATED_AT, entitlement.updatedAt());
    }

    @Test
    void publishedEntitlementCanBeUnpublished() {
        Entitlement published = unpublishedEntitlement().publish(PUBLISHED_AT);

        Entitlement unpublished = published.unpublish(UNPUBLISHED_AT);

        assertFalse(unpublished.requestable());
        assertEquals(UNPUBLISHED_AT, unpublished.updatedAt());
        assertEquals(CREATED_AT, unpublished.createdAt());
        assertEquals(0, unpublished.version());
        assertTrue(published.requestable());
        assertEquals(PUBLISHED_AT, published.updatedAt());
    }

    @Test
    void publishingAnAlreadyPublishedEntitlementIsIdempotent() {
        Entitlement published = unpublishedEntitlement().publish(PUBLISHED_AT);

        Entitlement publishedAgain = published.publish(UPDATED_AT);

        assertTrue(publishedAgain.requestable());
        assertEquals(PUBLISHED_AT, publishedAgain.updatedAt());
        assertEquals(published.version(), publishedAgain.version());
    }

    @Test
    void unpublishingAnAlreadyUnpublishedEntitlementIsIdempotent() {
        Entitlement entitlement = unpublishedEntitlement();

        Entitlement unpublishedAgain = entitlement.unpublish(UNPUBLISHED_AT);

        assertFalse(unpublishedAgain.requestable());
        assertEquals(CREATED_AT, unpublishedAgain.updatedAt());
        assertEquals(entitlement.version(), unpublishedAgain.version());
    }

    @Test
    void catalogDetailsCanBeUpdatedWithoutChangingTheResourceIdentity() {
        Entitlement published = unpublishedEntitlement().publish(PUBLISHED_AT);

        Entitlement updated = published.updateDetails(
                "Finance Viewer",
                "View-only access to finance data.",
                RiskLevel.MEDIUM,
                "finance-team-lead",
                UPDATED_AT);

        assertEquals("entitlement-1", updated.id());
        assertEquals("realm-1", updated.realmId());
        assertEquals(ResourceType.CLIENT_ROLE, updated.resourceType());
        assertEquals("role-1", updated.resourceId());
        assertEquals("Finance Viewer", updated.displayName());
        assertEquals("View-only access to finance data.", updated.description());
        assertEquals(RiskLevel.MEDIUM, updated.riskLevel());
        assertEquals("finance-team-lead", updated.approverRoleId());
        assertTrue(updated.requestable());
        assertEquals(CREATED_AT, updated.createdAt());
        assertEquals(UPDATED_AT, updated.updatedAt());
        assertEquals(0, updated.version());
    }

    @Test
    void catalogDetailUpdatesDoNotMutateThePreviousSnapshot() {
        Entitlement entitlement = unpublishedEntitlement();

        entitlement.updateDetails(
                "Finance Viewer",
                "View-only access to finance data.",
                RiskLevel.HIGH,
                "finance-team-lead",
                UPDATED_AT);

        assertEquals("Finance Reader", entitlement.displayName());
        assertEquals("Read-only access to the Finance Portal.", entitlement.description());
        assertEquals(RiskLevel.LOW, entitlement.riskLevel());
        assertEquals("finance-access-approver", entitlement.approverRoleId());
        assertEquals(CREATED_AT, entitlement.updatedAt());
    }

    @Test
    void rehydratedEntitlementRestoresItsCompleteCatalogState() {
        Entitlement entitlement = Entitlement.rehydrate(
                "entitlement-1",
                "realm-1",
                ResourceType.GROUP,
                "group-1",
                "Finance Accounting",
                "Membership of the Finance Accounting group.",
                RiskLevel.HIGH,
                "finance-access-approver",
                true,
                CREATED_AT,
                UPDATED_AT,
                7);

        assertEquals("entitlement-1", entitlement.id());
        assertEquals("realm-1", entitlement.realmId());
        assertEquals(ResourceType.GROUP, entitlement.resourceType());
        assertEquals("group-1", entitlement.resourceId());
        assertEquals("Finance Accounting", entitlement.displayName());
        assertEquals("Membership of the Finance Accounting group.", entitlement.description());
        assertEquals(RiskLevel.HIGH, entitlement.riskLevel());
        assertEquals("finance-access-approver", entitlement.approverRoleId());
        assertTrue(entitlement.requestable());
        assertEquals(CREATED_AT, entitlement.createdAt());
        assertEquals(UPDATED_AT, entitlement.updatedAt());
        assertEquals(7, entitlement.version());
    }

    @Test
    void entitlementCanBeCopiedWithThePersistedOptimisticLockVersion() {
        Entitlement entitlement = unpublishedEntitlement();

        Entitlement persisted = entitlement.withVersion(3);

        assertEquals(3, persisted.version());
        assertEquals(entitlement.id(), persisted.id());
        assertEquals(entitlement.realmId(), persisted.realmId());
        assertEquals(entitlement.resourceType(), persisted.resourceType());
        assertEquals(entitlement.resourceId(), persisted.resourceId());
        assertEquals(entitlement.displayName(), persisted.displayName());
        assertEquals(entitlement.description(), persisted.description());
        assertEquals(entitlement.riskLevel(), persisted.riskLevel());
        assertEquals(entitlement.approverRoleId(), persisted.approverRoleId());
        assertEquals(entitlement.requestable(), persisted.requestable());
        assertEquals(entitlement.createdAt(), persisted.createdAt());
        assertEquals(entitlement.updatedAt(), persisted.updatedAt());
    }

    @Test
    void entitlementRejectsNegativeOptimisticLockVersions() {
        assertThrows(IllegalArgumentException.class, () -> unpublishedEntitlement().withVersion(-1));
        assertThrows(IllegalArgumentException.class, () -> Entitlement.rehydrate(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                false,
                CREATED_AT,
                CREATED_AT,
                -1));
    }

    @Test
    void lifecycleChangesRejectTimestampsBeforeCreation() {
        Entitlement entitlement = unpublishedEntitlement();
        Instant beforeCreation = CREATED_AT.minusSeconds(1);

        assertThrows(IllegalArgumentException.class, () -> entitlement.publish(beforeCreation));
        assertThrows(IllegalArgumentException.class, () -> entitlement.updateDetails(
                "Finance Viewer",
                "View-only access to finance data.",
                RiskLevel.MEDIUM,
                "finance-team-lead",
                beforeCreation));
    }

    @Test
    void rehydrationRejectsAnUpdateTimestampBeforeCreation() {
        assertThrows(IllegalArgumentException.class, () -> Entitlement.rehydrate(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                false,
                UPDATED_AT,
                CREATED_AT,
                0));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void entitlementRequiresAnId(String id) {
        assertInvalidText(id, () -> createEntitlement(
                id,
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                CREATED_AT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void entitlementRequiresARealmId(String realmId) {
        assertInvalidText(realmId, () -> createEntitlement(
                "entitlement-1",
                realmId,
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                CREATED_AT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void entitlementRequiresAResourceId(String resourceId) {
        assertInvalidText(resourceId, () -> createEntitlement(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                resourceId,
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                CREATED_AT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void entitlementRequiresADisplayName(String displayName) {
        assertInvalidText(displayName, () -> createEntitlement(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                displayName,
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                CREATED_AT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void entitlementRequiresADescription(String description) {
        assertInvalidText(description, () -> createEntitlement(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                description,
                RiskLevel.LOW,
                "finance-access-approver",
                CREATED_AT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void entitlementRequiresAnApproverRoleId(String approverRoleId) {
        assertInvalidText(approverRoleId, () -> createEntitlement(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                approverRoleId,
                CREATED_AT));
    }

    @Test
    void entitlementRequiresAResourceTypeRiskLevelAndCreationTimestamp() {
        assertThrows(NullPointerException.class, () -> createEntitlement(
                "entitlement-1",
                "realm-1",
                null,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                CREATED_AT));
        assertThrows(NullPointerException.class, () -> createEntitlement(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                null,
                "finance-access-approver",
                CREATED_AT));
        assertThrows(NullPointerException.class, () -> createEntitlement(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                null));
    }

    @Test
    void catalogDetailUpdatesValidateAllReplacementMetadata() {
        Entitlement entitlement = unpublishedEntitlement();

        assertThrows(NullPointerException.class, () -> entitlement.updateDetails(
                null,
                "View-only access to finance data.",
                RiskLevel.MEDIUM,
                "finance-team-lead",
                UPDATED_AT));
        assertThrows(NullPointerException.class, () -> entitlement.updateDetails(
                "Finance Viewer",
                null,
                RiskLevel.MEDIUM,
                "finance-team-lead",
                UPDATED_AT));
        assertThrows(NullPointerException.class, () -> entitlement.updateDetails(
                "Finance Viewer",
                "View-only access to finance data.",
                null,
                "finance-team-lead",
                UPDATED_AT));
        assertThrows(NullPointerException.class, () -> entitlement.updateDetails(
                "Finance Viewer",
                "View-only access to finance data.",
                RiskLevel.MEDIUM,
                null,
                UPDATED_AT));
        assertThrows(NullPointerException.class, () -> entitlement.updateDetails(
                "Finance Viewer",
                "View-only access to finance data.",
                RiskLevel.MEDIUM,
                "finance-team-lead",
                null));
    }

    @Test
    void lifecycleChangesRequireATimestamp() {
        Entitlement entitlement = unpublishedEntitlement();

        assertThrows(NullPointerException.class, () -> entitlement.publish(null));
        assertThrows(NullPointerException.class, () -> entitlement.unpublish(null));
    }

    private static Entitlement unpublishedEntitlement() {
        return createEntitlement(
                "entitlement-1",
                "realm-1",
                ResourceType.CLIENT_ROLE,
                "role-1",
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                "finance-access-approver",
                CREATED_AT);
    }

    private static void assertInvalidText(String value, Executable executable) {
        if (value == null) {
            assertThrows(NullPointerException.class, executable);
        } else {
            assertThrows(IllegalArgumentException.class, executable);
        }
    }

    private static Entitlement createEntitlement(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            String description,
            RiskLevel riskLevel,
            String approverRoleId,
            Instant createdAt) {
        return Entitlement.create(
                id,
                realmId,
                resourceType,
                resourceId,
                displayName,
                description,
                riskLevel,
                approverRoleId,
                createdAt);
    }
}
