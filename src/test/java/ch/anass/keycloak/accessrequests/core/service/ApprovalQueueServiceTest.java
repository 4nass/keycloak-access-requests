package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueEntry;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.RoleMembershipReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalQueueServiceTest {

    private final InMemoryApprovalQueue approvalQueue = new InMemoryApprovalQueue();
    private final InMemoryEntitlementApprovalScopes entitlementApprovalScopes = new InMemoryEntitlementApprovalScopes();
    private final InMemoryRoleMembershipReader roleMembershipReader = new InMemoryRoleMembershipReader();

    @Test
    void returnsOnlyPendingRequestsWithinTheApproversEntitlementScope() {
        approvalQueue.add(pending("request-finance", "realm-1", "requester-1", "finance", 1));
        approvalQueue.add(pending("request-hr", "realm-1", "requester-2", "hr", 2));
        approvalQueue.add(pending("request-own", "realm-1", "approver-1", "finance", 3));
        approvalQueue.add(approved("request-approved", "realm-1", "requester-3", "finance", 4));
        approvalQueue.add(pending("request-other-realm", "realm-2", "requester-4", "finance", 5));
        approvalQueue.configuresEntitlement("finance", "finance-approver", RiskLevel.HIGH);
        approvalQueue.configuresEntitlement("hr", "hr-approver");
        roleMembershipReader.grantsRole("realm-1", "approver-1", "finance-approver");

        ApprovalQueuePage page = queue("realm-1", "approver-1", 0, 20);

        assertEquals(List.of("request-finance"), page.items().stream()
                .map(entry -> entry.request().id()).toList());
        assertEquals(RiskLevel.HIGH, page.items().getFirst().riskLevel());
        assertEquals(1, page.total());
        assertTrue(approvalQueue.wasQueried());
    }

    @Test
    void paginatesAfterFilteringRequestsByApproverScope() {
        approvalQueue.add(pending("eligible-old", "realm-1", "requester-1", "finance", 1));
        approvalQueue.add(pending("ineligible-new", "realm-1", "requester-2", "hr", 4));
        approvalQueue.add(pending("eligible-middle", "realm-1", "requester-3", "finance", 2));
        approvalQueue.add(pending("eligible-new", "realm-1", "requester-4", "finance", 3));
        approvalQueue.configuresEntitlement("finance", "finance-approver");
        approvalQueue.configuresEntitlement("hr", "hr-approver");
        roleMembershipReader.grantsRole("realm-1", "approver-1", "finance-approver");

        ApprovalQueuePage page = queue("realm-1", "approver-1", 1, 1);

        assertEquals(List.of("eligible-middle"), page.items().stream()
                .map(entry -> entry.request().id()).toList());
        assertEquals(3, page.total());
        assertEquals(1, page.page());
        assertEquals(1, page.size());
    }

    @Test
    void returnsAnEmptyQueueWithoutQueryingPendingRequestsWhenTheApproverHasNoRoles() {
        approvalQueue.add(pending("request-finance", "realm-1", "requester-1", "finance", 1));
        approvalQueue.configuresEntitlement("finance", "finance-approver");

        ApprovalQueuePage page = queue("realm-1", "approver-1", 0, 20);

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertFalse(approvalQueue.wasQueried());
    }

    @Test
    void doesNotUseRolesGrantedToTheApproverInAnotherRealm() {
        approvalQueue.add(pending("request-finance", "realm-1", "requester-1", "finance", 1));
        approvalQueue.configuresEntitlement("finance", "finance-approver");
        roleMembershipReader.grantsRole("realm-2", "approver-1", "finance-approver");

        ApprovalQueuePage page = queue("realm-1", "approver-1", 0, 20);

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertFalse(approvalQueue.wasQueried());
    }

    @Test
    void identifiesAnApproverWithoutRevealingOrRequiringPendingRequests() {
        entitlementApprovalScopes.configuresRequestableEntitlement("realm-1", "finance-approver");
        roleMembershipReader.grantsRole("realm-1", "approver-1", "finance-approver");

        assertTrue(approvalQueueService().canApprove("realm-1", "approver-1"));
    }

    @Test
    void doesNotIdentifyAUserAsApproverForAnUnrelatedRole() {
        entitlementApprovalScopes.configuresRequestableEntitlement("realm-1", "finance-approver");
        roleMembershipReader.grantsRole("realm-1", "approver-1", "unrelated-role");

        assertFalse(approvalQueueService().canApprove("realm-1", "approver-1"));
    }

    private ApprovalQueuePage queue(String realmId, String approverId, int page, int size) {
        return approvalQueueService()
                .findPending(realmId, approverId, page, size);
    }

    private ApprovalQueueService approvalQueueService() {
        return new ApprovalQueueService(approvalQueue, entitlementApprovalScopes, roleMembershipReader);
    }

    private static AccessRequest pending(
            String id, String realmId, String requesterId, String entitlementId, long createdAtSeconds) {
        return request(id, realmId, requesterId, entitlementId, createdAtSeconds);
    }

    private static AccessRequest approved(
            String id, String realmId, String requesterId, String entitlementId, long createdAtSeconds) {
        AccessRequest request = request(id, realmId, requesterId, entitlementId, createdAtSeconds);
        request.approve("approver-2", "Approved.", Instant.ofEpochSecond(createdAtSeconds + 1));
        return request;
    }

    private static AccessRequest request(
            String id, String realmId, String requesterId, String entitlementId, long createdAtSeconds) {
        return AccessRequest.create(
                id,
                realmId,
                requesterId,
                entitlementId,
                ResourceType.REALM_ROLE,
                "role-" + entitlementId,
                "Resource " + entitlementId,
                "Access is needed for the project.",
                Instant.ofEpochSecond(createdAtSeconds));
    }

    private static final class InMemoryApprovalQueue implements AccessRequestRepository {

        private final List<AccessRequest> requests = new ArrayList<>();
        private final Map<String, String> approverRoles = new HashMap<>();
        private final Map<String, RiskLevel> riskLevels = new HashMap<>();
        private boolean queried;

        void add(AccessRequest request) {
            requests.add(request);
        }

        void configuresEntitlement(String entitlementId, String approverRoleId) {
            configuresEntitlement(entitlementId, approverRoleId, RiskLevel.LOW);
        }

        void configuresEntitlement(String entitlementId, String approverRoleId, RiskLevel riskLevel) {
            approverRoles.put(entitlementId, approverRoleId);
            riskLevels.put(entitlementId, riskLevel);
        }

        @Override
        public Optional<AccessRequest> findById(String realmId, String requestId) {
            throw new UnsupportedOperationException("Request reads are not used by this test double.");
        }

        @Override
        public AccessRequestPage findByRequester(AccessRequestQuery query) {
            throw new UnsupportedOperationException("Requester request reads are not used by this test double.");
        }

        @Override
        public ApprovalQueuePage findPendingForApprover(ApprovalQueueQuery query) {
            queried = true;
            return findPending(query);
        }

        @Override
        public Set<String> findPendingEntitlementIds(
                String realmId,
                String requesterId,
                Set<String> entitlementIds) {
            throw new UnsupportedOperationException("Catalog reads are not used by this test double.");
        }

        @Override
        public Optional<AccessRequest> createIfNoPending(AccessRequest request) {
            throw new UnsupportedOperationException("Request creation is not used by this test double.");
        }

        @Override
        public Optional<AccessRequest> updateIfVersionMatches(AccessRequest request, long expectedVersion) {
            throw new UnsupportedOperationException("Request updates are not used by this test double.");
        }

        boolean wasQueried() {
            return queried;
        }

        private ApprovalQueuePage findPending(ApprovalQueueQuery query) {
            List<ApprovalQueueEntry> matching = requests.stream()
                    .filter(request -> request.realmId().equals(query.realmId()))
                    .filter(request -> request.decisionStatus() == DecisionStatus.PENDING)
                    .filter(request -> !request.requesterId().equals(query.approverId()))
                    .filter(request -> query.approverRoleIds().contains(approverRoles.get(request.entitlementId())))
                    .sorted(Comparator.comparing(AccessRequest::createdAt).reversed())
                    .map(request -> new ApprovalQueueEntry(
                            request.copy(), riskLevels.get(request.entitlementId())))
                    .toList();
            int from = Math.min(query.offset(), matching.size());
            int to = Math.min(from + query.size(), matching.size());
            return new ApprovalQueuePage(matching.subList(from, to), query.page(), query.size(), matching.size());
        }
    }

    private static final class InMemoryRoleMembershipReader implements RoleMembershipReader {

        private final Map<String, Set<String>> memberships = new HashMap<>();

        void grantsRole(String realmId, String approverId, String roleId) {
            memberships.merge(
                    membershipKey(realmId, approverId),
                    Set.of(roleId),
                    (current, added) -> {
                        var merged = new java.util.HashSet<>(current);
                        merged.addAll(added);
                        return Set.copyOf(merged);
                    });
        }

        @Override
        public boolean hasRole(String realmId, String actorId, String roleId) {
            return findEffectiveRoleIds(realmId, actorId).contains(roleId);
        }

        @Override
        public Set<String> findEffectiveRoleIds(String realmId, String actorId) {
            return memberships.getOrDefault(membershipKey(realmId, actorId), Set.of());
        }

        private static String membershipKey(String realmId, String approverId) {
            return realmId + ":" + approverId;
        }
    }

    private static final class InMemoryEntitlementApprovalScopes implements EntitlementRepository {

        private final Map<String, Set<String>> requestableApproverRoleIds = new HashMap<>();

        void configuresRequestableEntitlement(String realmId, String approverRoleId) {
            requestableApproverRoleIds.merge(realmId, Set.of(approverRoleId), (current, added) -> {
                var merged = new java.util.HashSet<>(current);
                merged.addAll(added);
                return Set.copyOf(merged);
            });
        }

        @Override
        public Optional<Entitlement> findById(String realmId, String entitlementId) {
            throw new UnsupportedOperationException("Entitlement reads are not used by this test double.");
        }

        @Override
        public Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId) {
            throw new UnsupportedOperationException("Entitlement reads are not used by this test double.");
        }

        @Override
        public CatalogPage findRequestable(CatalogQuery query) {
            throw new UnsupportedOperationException("Catalog reads are not used by this test double.");
        }

        @Override
        public boolean hasRequestableEntitlementForApproverRoles(String realmId, Set<String> approverRoleIds) {
            return requestableApproverRoleIds.getOrDefault(realmId, Set.of()).stream()
                    .anyMatch(approverRoleIds::contains);
        }
    }
}
