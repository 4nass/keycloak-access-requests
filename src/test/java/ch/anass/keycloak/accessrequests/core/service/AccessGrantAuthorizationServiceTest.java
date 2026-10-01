package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationAuthority;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Authorization must read current policy and persist the transition atomically. */
class AccessGrantAuthorizationServiceTest {

    private static final Instant GRANTED_AT = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void authorizesWithCurrentPolicyUnderGrantAndEntitlementLocks() {
        Fixture fixture = new Fixture(true, true);

        fixture.service().authorize("realm-1", "request-1");

        assertTrue(fixture.grants.locked);
        assertTrue(fixture.entitlements.locked);
        assertTrue(fixture.transactionEntered);
        assertEquals(GrantRevocationState.AUTHORIZED, fixture.grants.current.revocationState());
        assertEquals(1, fixture.grants.current.version());
    }

    @Test
    void refusesAnOptedOutOrUnverifiableResourceWithoutWritingTheGrant() {
        for (Fixture fixture : new Fixture[] {new Fixture(false, true), new Fixture(true, false)}) {
            assertThrows(IllegalStateException.class,
                    () -> fixture.service().authorize("realm-1", "request-1"));
            assertEquals(GrantRevocationState.UNVERIFIED, fixture.grants.current.revocationState());
            assertEquals(0, fixture.grants.writes);
        }
    }

    @Test
    void invalidationWinningTheRaceCannotBeOverwrittenByStaleAuthorization() {
        Fixture fixture = new Fixture(true, true);
        fixture.grants.invalidateBeforeWrite = true;

        assertThrows(ConcurrentGrantModificationException.class,
                () -> fixture.service().authorize("realm-1", "request-1"));
        assertEquals(GrantRevocationState.INVALIDATED, fixture.grants.current.revocationState());
        assertEquals(1, fixture.grants.current.version());
    }

    @Test
    void crossRealmLookupNeverAuthorizesTheGrant() {
        Fixture fixture = new Fixture(true, true);

        fixture.service().authorize("other-realm", "request-1");

        assertFalse(fixture.grants.locked);
        assertEquals(0, fixture.grants.writes);
        assertEquals(GrantRevocationState.UNVERIFIED, fixture.grants.current.revocationState());
    }

    @Test
    void repeatedAuthorizationDoesNotIncrementVersionAgain() {
        Fixture fixture = new Fixture(true, true);

        fixture.service().authorize("realm-1", "request-1");
        fixture.service().authorize("realm-1", "request-1");

        assertEquals(1, fixture.grants.writes);
        assertEquals(1, fixture.grants.current.version());
    }

    @Test
    void authorizesAGroupDeliveredPackageWithoutTheLegacyDirectRolePolicyFlag() {
        Fixture fixture = new Fixture(false, true, true);

        fixture.service().authorize("realm-1", "request-1");
        fixture.service().authorize("realm-1", "request-1");

        assertTrue(fixture.grants.locked);
        assertTrue(fixture.entitlements.locked);
        assertEquals(ResourceType.GROUP, fixture.grants.current.resourceType());
        assertEquals("package-group-1", fixture.grants.current.deliveryGroupId());
        assertEquals(GrantRevocationState.AUTHORIZED, fixture.grants.current.revocationState());
        assertEquals(1, fixture.grants.writes, "Repeated authorization must not rewrite the grant");
    }

    @Test
    void aPackageCannotBeAuthorizedWhenIndependentOwnershipVerificationFails() {
        Fixture fixture = new Fixture(false, false, true);

        assertThrows(IllegalStateException.class, () -> fixture.service().authorize("realm-1", "request-1"));

        assertTrue(fixture.grants.locked);
        assertTrue(fixture.entitlements.locked);
        assertEquals(GrantRevocationState.UNVERIFIED, fixture.grants.current.revocationState());
        assertEquals(0, fixture.grants.writes);
    }

    private static final class Fixture {
        private final GrantStore grants;
        private final EntitlementStore entitlements;
        private final boolean exclusiveManagementVerified;
        private boolean insideTransaction;
        private boolean transactionEntered;

        private Fixture(boolean declaredExclusiveJit, boolean exclusiveManagementVerified) {
            this(declaredExclusiveJit, exclusiveManagementVerified, false);
        }

        private Fixture(boolean declaredExclusiveJit, boolean exclusiveManagementVerified, boolean packageGrant) {
            grants = new GrantStore(packageGrant);
            entitlements = new EntitlementStore(declaredExclusiveJit, packageGrant);
            this.exclusiveManagementVerified = exclusiveManagementVerified;
        }

        private AccessGrantAuthorizationService service() {
            AccessGrantRevocationAuthority authority = grant -> {
                assertTrue(insideTransaction);
                assertTrue(grants.locked);
                assertTrue(entitlements.locked);
                return exclusiveManagementVerified;
            };
            AccessRequestTransaction transaction = new AccessRequestTransaction() {
                @Override
                public <T> T execute(Supplier<T> operation) {
                    transactionEntered = true;
                    insideTransaction = true;
                    try {
                        return operation.get();
                    } finally {
                        insideTransaction = false;
                    }
                }
            };
            return new AccessGrantAuthorizationService(grants, entitlements, authority, transaction);
        }
    }

    private static final class EntitlementStore implements EntitlementRepository {
        private final Entitlement current;
        private boolean locked;

        private EntitlementStore(boolean declaredExclusiveJit, boolean packageGrant) {
            current = Entitlement.create("entitlement-1", "realm-1",
                    packageGrant ? ResourceType.GROUP : ResourceType.REALM_ROLE,
                    packageGrant ? "package-group-1" : "jit-role-1", "JIT role", "Dedicated",
                    RiskLevel.LOW, "approver-role", GRANTED_AT)
                    .withExclusiveJit(declaredExclusiveJit, GRANTED_AT);
        }

        @Override
        public Optional<Entitlement> findById(String realmId, String entitlementId) {
            return current.realmId().equals(realmId) && current.id().equals(entitlementId)
                    ? Optional.of(current) : Optional.empty();
        }

        @Override
        public Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId) {
            locked = true;
            return findById(realmId, entitlementId);
        }

        @Override
        public CatalogPage findRequestable(CatalogQuery query) {
            throw new UnsupportedOperationException("Not needed for grant authorization");
        }
    }

    private static final class GrantStore implements AccessGrantRevocationRepository {
        private AccessGrant current;
        private boolean locked;
        private boolean invalidateBeforeWrite;
        private int writes;

        private GrantStore(boolean packageGrant) {
            current = new AccessGrant("request-1", "realm-1", "user-1", "entitlement-1",
                    packageGrant ? ResourceType.GROUP : ResourceType.REALM_ROLE,
                    packageGrant ? "package-group-1" : "jit-role-1", GrantOrigin.CREATED_BY_EXTENSION, GRANTED_AT,
                    GRANTED_AT.plusSeconds(3600), GrantRevocationState.UNVERIFIED, 0,
                    packageGrant ? "package-group-1" : null);
        }

        @Override
        public void create(AccessGrant grant) {
            throw new UnsupportedOperationException("The grant already exists");
        }

        @Override
        public Optional<AccessGrant> findByRequestId(String realmId, String requestId) {
            return current.realmId().equals(realmId) && current.requestId().equals(requestId)
                    ? Optional.of(current) : Optional.empty();
        }

        @Override
        public Optional<AccessGrant> findByRequestIdForUpdate(String realmId, String requestId) {
            Optional<AccessGrant> found = findByRequestId(realmId, requestId);
            locked = found.isPresent();
            return found;
        }

        @Override
        public Optional<AccessGrant> invalidateIfVersionMatches(String realmId, String requestId, long expectedVersion) {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        public Optional<AccessGrant> updateIfVersionMatches(AccessGrant updated, long expectedVersion) {
            if (invalidateBeforeWrite) {
                current = current.invalidate();
                current = withVersion(current, expectedVersion + 1);
                invalidateBeforeWrite = false;
            }
            if (current.version() != expectedVersion || current.revocationState() != GrantRevocationState.UNVERIFIED
                    || updated.revocationState() != GrantRevocationState.AUTHORIZED) {
                return Optional.empty();
            }
            current = withVersion(updated, expectedVersion + 1);
            writes++;
            return Optional.of(current);
        }

        private static AccessGrant withVersion(AccessGrant grant, long version) {
            return new AccessGrant(grant.requestId(), grant.realmId(), grant.requesterId(), grant.entitlementId(),
                    grant.resourceType(), grant.resourceId(), grant.origin(), grant.recordedAt(), grant.expiresAt(),
                    grant.revocationState(), version, grant.deliveryGroupId());
        }
    }
}
