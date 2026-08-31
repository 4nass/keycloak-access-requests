package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
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
    private final InMemoryRoleMembershipReader roleMembershipReader = new InMemoryRoleMembershipReader();

    @Test
    void returnsOnlyPendingRequestsWithinTheApproversEntitlementScope() {
        approvalQueue.add(pending("request-finance", "realm-1", "requester-1", "finance", 1));
        approvalQueue.add(pending("request-hr", "realm-1", "requester-2", "hr", 2));
        approvalQueue.add(pending("request-own", "realm-1", "approver-1", "finance", 3));
        approvalQueue.add(approved("request-approved", "realm-1", "requester-3", "finance", 4));
        approvalQueue.add(pending("request-other-realm", "realm-2", "requester-4", "finance", 5));
        approvalQueue.configuresEntitlement("finance", "finance-approver");
        approvalQueue.configuresEntitlement("hr", "hr-approver");
        roleMembershipReader.grantsRole("realm-1", "approver-1", "finance-approver");

        AccessRequestPage page = queue("realm-1", "approver-1", 0, 20);

        assertEquals(List.of("request-finance"), page.items().stream().map(AccessRequest::id).toList());
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

        AccessRequestPage page = queue("realm-1", "approver-1", 1, 1);

        assertEquals(List.of("eligible-middle"), page.items().stream().map(AccessRequest::id).toList());
        assertEquals(3, page.total());
        assertEquals(1, page.page());
        assertEquals(1, page.size());
    }

    @Test
    void returnsAnEmptyQueueWithoutQueryingPendingRequestsWhenTheApproverHasNoRoles() {
        approvalQueue.add(pending("request-finance", "realm-1", "requester-1", "finance", 1));
        approvalQueue.configuresEntitlement("finance", "finance-approver");

        AccessRequestPage page = queue("realm-1", "approver-1", 0, 20);

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertFalse(approvalQueue.wasQueried());
    }

    @Test
    void doesNotUseRolesGrantedToTheApproverInAnotherRealm() {
        approvalQueue.add(pending("request-finance", "realm-1", "requester-1", "finance", 1));
        approvalQueue.configuresEntitlement("finance", "finance-approver");
        roleMembershipReader.grantsRole("realm-2", "approver-1", "finance-approver");

        AccessRequestPage page = queue("realm-1", "approver-1", 0, 20);

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertFalse(approvalQueue.wasQueried());
    }

    private AccessRequestPage queue(String realmId, String approverId, int page, int size) {
        return new ApprovalQueueService(approvalQueue, roleMembershipReader)
                .findPending(realmId, approverId, page, size);
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
        private boolean queried;

        void add(AccessRequest request) {
            requests.add(request);
        }

        void configuresEntitlement(String entitlementId, String approverRoleId) {
            approverRoles.put(entitlementId, approverRoleId);
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
        public AccessRequestPage findPendingForApprover(ApprovalQueueQuery query) {
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

        private AccessRequestPage findPending(ApprovalQueueQuery query) {
            List<AccessRequest> matching = requests.stream()
                    .filter(request -> request.realmId().equals(query.realmId()))
                    .filter(request -> request.decisionStatus() == DecisionStatus.PENDING)
                    .filter(request -> !request.requesterId().equals(query.approverId()))
                    .filter(request -> query.approverRoleIds().contains(approverRoles.get(request.entitlementId())))
                    .sorted(Comparator.comparing(AccessRequest::createdAt).reversed())
                    .map(AccessRequest::copy)
                    .toList();
            int from = Math.min(query.offset(), matching.size());
            int to = Math.min(from + query.size(), matching.size());
            return new AccessRequestPage(matching.subList(from, to), query.page(), query.size(), matching.size());
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
}
