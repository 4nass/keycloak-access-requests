package ch.anass.keycloak.accessrequests.core.domain.approval;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import java.util.Objects;

/**
 * A pending request together with the approval context configured for its entitlement.
 */
public record ApprovalQueueEntry(AccessRequest request, RiskLevel riskLevel) {

    public ApprovalQueueEntry {
        request = Objects.requireNonNull(request, "request must not be null");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }
}
