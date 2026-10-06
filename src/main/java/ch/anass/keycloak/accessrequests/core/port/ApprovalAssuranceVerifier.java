package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;

@FunctionalInterface
public interface ApprovalAssuranceVerifier {
    void verify(RiskLevel riskLevel);
}
