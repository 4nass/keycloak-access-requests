package ch.anass.keycloak.accessrequests.core.domain;

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
