package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.RoleMembershipReader;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Finds pending access requests that the current approver is allowed to decide.
 */
public final class ApprovalQueueService {

    private final AccessRequestRepository accessRequestRepository;
    private final RoleMembershipReader roleMembershipReader;

    public ApprovalQueueService(
            AccessRequestRepository accessRequestRepository,
            RoleMembershipReader roleMembershipReader) {
        this.accessRequestRepository = Objects.requireNonNull(accessRequestRepository);
        this.roleMembershipReader = Objects.requireNonNull(roleMembershipReader);
    }

    public ApprovalQueuePage findPending(String realmId, String approverId, int page, int size) {
        Set<String> approverRoleIds = roleMembershipReader.findEffectiveRoleIds(realmId, approverId);
        ApprovalQueueQuery query = new ApprovalQueueQuery(realmId, approverId, approverRoleIds, page, size);
        if (approverRoleIds.isEmpty()) {
            return new ApprovalQueuePage(List.of(), page, size, 0);
        }
        return accessRequestRepository.findPendingForApprover(query);
    }
}
