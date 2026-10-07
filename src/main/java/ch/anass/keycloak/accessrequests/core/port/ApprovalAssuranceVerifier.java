package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalAssuranceEvidence;

@FunctionalInterface
public interface ApprovalAssuranceVerifier {
    ApprovalAssuranceEvidence verify(RiskLevel riskLevel);
}
