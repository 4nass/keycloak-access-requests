package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalQueueServiceTest {

    private final InMemoryApprovalQueue approvalQueue = new InMemoryApprovalQueue();

    @Test
    void returnsOnlyPendingRequestsWithinTheApproversEntitlementScope() {
        approvalQueue.add(pending("request-finance", "realm-1", "requester-1", "finance", 1));
        approvalQueue.add(pending("request-hr", "realm-1", "requester-2", "hr", 2));
        approvalQueue.add(pending("request-own", "realm-1", "approver-1", "finance", 3));
        approvalQueue.add(approved("request-approved", "realm-1", "requester-3", "finance", 4));
        approvalQueue.add(pending("request-other-realm", "realm-2", "requester-4", "finance", 5));
        approvalQueue.configuresEntitlement("finance", "finance-approver");
        approvalQueue.configuresEntitlement("hr", "hr-approver");
        approvalQueue.grantsRole("realm-1", "approver-1", "finance-approver");

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
        approvalQueue.grantsRole("realm-1", "approver-1", "finance-approver");

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
        approvalQueue.grantsRole("realm-2", "approver-1", "finance-approver");

        AccessRequestPage page = queue("realm-1", "approver-1", 0, 20);

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertFalse(approvalQueue.wasQueried());
    }

    private AccessRequestPage queue(String realmId, String approverId, int page, int size) {
        try {
            Class<?> roleMembershipReader = Class.forName(
                    "ch.anass.keycloak.accessrequests.core.port.RoleMembershipReader");
            Object service = Class.forName(
                            "ch.anass.keycloak.accessrequests.core.service.ApprovalQueueService")
                    .getDeclaredConstructor(AccessRequestRepository.class, roleMembershipReader)
                    .newInstance(approvalQueue.repository(), approvalQueue.roleMembershipReader());
            Method findPending = service.getClass().getMethod(
                    "findPending", String.class, String.class, int.class, int.class);
            return (AccessRequestPage) findPending.invoke(service, realmId, approverId, page, size);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "The core must provide an approval queue service, query, and repository port.",
                    exception);
        }
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

    private static final class InMemoryApprovalQueue {

        private final List<AccessRequest> requests = new ArrayList<>();
        private final Map<String, String> approverRoles = new HashMap<>();
        private final Map<String, Set<String>> memberships = new HashMap<>();
        private boolean queried;

        void add(AccessRequest request) {
            requests.add(request);
        }

        void configuresEntitlement(String entitlementId, String approverRoleId) {
            approverRoles.put(entitlementId, approverRoleId);
        }

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

        Object repository() {
            return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{AccessRequestRepository.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("findPendingForApprover")) {
                            queried = true;
                            return findPending(arguments[0]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        Object roleMembershipReader() throws ReflectiveOperationException {
            Class<?> roleMembershipReader = Class.forName(
                    "ch.anass.keycloak.accessrequests.core.port.RoleMembershipReader");
            return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{roleMembershipReader},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("findEffectiveRoleIds")) {
                            return memberships.getOrDefault(
                                    membershipKey((String) arguments[0], (String) arguments[1]),
                                    Set.of());
                        }
                        if (method.getName().equals("hasRole")) {
                            return memberships.getOrDefault(
                                            membershipKey((String) arguments[0], (String) arguments[1]),
                                            Set.of())
                                    .contains(arguments[2]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        boolean wasQueried() {
            return queried;
        }

        private AccessRequestPage findPending(Object query) throws ReflectiveOperationException {
            String realmId = (String) property(query, "realmId");
            String approverId = (String) property(query, "approverId");
            @SuppressWarnings("unchecked")
            Set<String> roleIds = (Set<String>) property(query, "approverRoleIds");
            int page = (int) property(query, "page");
            int size = (int) property(query, "size");
            List<AccessRequest> matching = requests.stream()
                    .filter(request -> request.realmId().equals(realmId))
                    .filter(request -> request.decisionStatus() == DecisionStatus.PENDING)
                    .filter(request -> !request.requesterId().equals(approverId))
                    .filter(request -> roleIds.contains(approverRoles.get(request.entitlementId())))
                    .sorted(Comparator.comparing(AccessRequest::createdAt).reversed())
                    .map(AccessRequest::copy)
                    .toList();
            int from = Math.min(page * size, matching.size());
            int to = Math.min(from + size, matching.size());
            return new AccessRequestPage(matching.subList(from, to), page, size, matching.size());
        }

        private static Object property(Object value, String name) throws ReflectiveOperationException {
            return value.getClass().getMethod(name).invoke(value);
        }

        private static String membershipKey(String realmId, String approverId) {
            return realmId + ":" + approverId;
        }
    }
}
