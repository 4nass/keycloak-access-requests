package ch.anass.keycloak.accessrequests.core.domain.approval;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalAssurancePolicyTest {

    @Test
    void defaultsProtectHighAndCriticalWithoutAffectingLowerRisks() {
        ApprovalAssurancePolicy policy = ApprovalAssurancePolicy.defaults();

        assertNull(policy.requirementFor(RiskLevel.LOW));
        assertNull(policy.requirementFor(RiskLevel.MEDIUM));
        assertEquals(new ApprovalAssurancePolicy.Requirement("2", 2, 1800),
                policy.requirementFor(RiskLevel.HIGH));
        assertEquals(new ApprovalAssurancePolicy.Requirement("2", 2, 300),
                policy.requirementFor(RiskLevel.CRITICAL));
    }

    @Test
    void administratorsMayChooseRealmAcrAndStricterFreshness() {
        ApprovalAssurancePolicy policy = new ApprovalAssurancePolicy(
                new ApprovalAssurancePolicy.Requirement("strong", 2, 900),
                new ApprovalAssurancePolicy.Requirement("phishing-resistant", 3, 120));

        assertEquals("phishing-resistant", policy.requirementFor(RiskLevel.CRITICAL).acr());
    }

    @Test
    void rejectsWeakerOrUnboundedSettings() {
        var strong = new ApprovalAssurancePolicy.Requirement("strong", 2, 900);
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalAssurancePolicy.Requirement(" ", 2, 300));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalAssurancePolicy.Requirement("weak", 1, 300));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalAssurancePolicy.Requirement("strong", 2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalAssurancePolicy(new ApprovalAssurancePolicy.Requirement("strong", 2, 3601), strong));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalAssurancePolicy(strong,
                        new ApprovalAssurancePolicy.Requirement("strong", 2, 301)));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalAssurancePolicy(new ApprovalAssurancePolicy.Requirement("very-strong", 3, 900), strong));
    }
}
