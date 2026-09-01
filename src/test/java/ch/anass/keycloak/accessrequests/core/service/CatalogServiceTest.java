package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogResult;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogServiceTest {

    @Test
    void enrichesRequestableEntitlementsWithTheRequestersAvailability() {
        CatalogQuery query = new CatalogQuery("realm-1", ResourceType.CLIENT_ROLE, "finance", RiskLevel.LOW, 0, 20);
        Entitlement granted = entitlement("entitlement-1", "Finance Reader");
        Entitlement pending = entitlement("entitlement-2", "Finance Editor");
        RecordingEntitlementRepository entitlements = new RecordingEntitlementRepository(
                new CatalogPage(List.of(granted, pending), 0, 20, 2));
        RecordingAccessRequestRepository requests = new RecordingAccessRequestRepository(Set.of(pending.id()));
        EffectiveAccessChecker effectiveAccess = (realmId, requesterId, entitlement) -> entitlement.id().equals(granted.id());

        CatalogResult result = new CatalogService(entitlements, requests, effectiveAccess)
                .findRequestable(query, "requester-1");

        assertSame(query, entitlements.lastQuery());
        assertEquals("realm-1", requests.lastRealmId());
        assertEquals("requester-1", requests.lastRequesterId());
        assertEquals(Set.of(granted.id(), pending.id()), requests.lastEntitlementIds());
        assertEquals(0, result.page());
        assertEquals(20, result.size());
        assertEquals(2, result.total());
        assertTrue(result.items().get(0).alreadyGranted());
        assertEquals(false, result.items().get(0).pendingRequest());
        assertEquals(false, result.items().get(1).alreadyGranted());
        assertTrue(result.items().get(1).pendingRequest());
    }

    @Test
    void rejectsMissingCatalogInputs() {
        CatalogService service = new CatalogService(
                new RecordingEntitlementRepository(new CatalogPage(List.of(), 0, 20, 0)),
                new RecordingAccessRequestRepository(Set.of()),
                (realmId, requesterId, entitlement) -> false);

        assertThrows(NullPointerException.class, () -> service.findRequestable(null, "requester-1"));
        assertThrows(NullPointerException.class, () -> service.findRequestable(
                new CatalogQuery("realm-1", null, null, null, 0, 20), null));
        assertThrows(IllegalArgumentException.class, () -> service.findRequestable(
                new CatalogQuery("realm-1", null, null, null, 0, 20), " "));
    }

    private static Entitlement entitlement(String id, String displayName) {
        return Entitlement.create(
                        id,
                        "realm-1",
                        ResourceType.CLIENT_ROLE,
                        "role-" + id,
                        displayName,
                        "Read-only access to finance data.",
                        RiskLevel.LOW,
                        "finance-access-approver",
                        Instant.EPOCH)
                .publish(Instant.EPOCH);
    }

    private static final class RecordingEntitlementRepository implements EntitlementRepository {

        private final CatalogPage page;
        private CatalogQuery lastQuery;

        private RecordingEntitlementRepository(CatalogPage page) {
            this.page = page;
        }

        @Override
        public Optional<Entitlement> findById(String realmId, String entitlementId) {
            return Optional.empty();
        }

        @Override
        public CatalogPage findRequestable(CatalogQuery query) {
            lastQuery = query;
            return page;
        }

        private CatalogQuery lastQuery() {
            return lastQuery;
        }
    }

    private static final class RecordingAccessRequestRepository implements AccessRequestRepository {

        private final Set<String> pendingEntitlementIds;
        private String lastRealmId;
        private String lastRequesterId;
        private Set<String> lastEntitlementIds;

        private RecordingAccessRequestRepository(Set<String> pendingEntitlementIds) {
            this.pendingEntitlementIds = pendingEntitlementIds;
        }

        @Override
        public Optional<AccessRequest> findById(String realmId, String requestId) {
            return Optional.empty();
        }

        @Override
        public AccessRequestPage findByRequester(AccessRequestQuery query) {
            throw new UnsupportedOperationException("Requester request reads are not used by this test double.");
        }

        @Override
        public ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage findPendingForApprover(
                ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery query) {
            throw new UnsupportedOperationException("Approval queue reads are not used by this test double.");
        }

        @Override
        public Set<String> findPendingEntitlementIds(String realmId, String requesterId, Set<String> entitlementIds) {
            lastRealmId = realmId;
            lastRequesterId = requesterId;
            lastEntitlementIds = entitlementIds;
            return pendingEntitlementIds;
        }

        @Override
        public Optional<AccessRequest> createIfNoPending(AccessRequest request) {
            return Optional.empty();
        }

        @Override
        public Optional<AccessRequest> updateIfVersionMatches(AccessRequest request, long expectedVersion) {
            return Optional.empty();
        }

        private String lastRealmId() {
            return lastRealmId;
        }

        private String lastRequesterId() {
            return lastRequesterId;
        }

        private Set<String> lastEntitlementIds() {
            return lastEntitlementIds;
        }
    }
}
