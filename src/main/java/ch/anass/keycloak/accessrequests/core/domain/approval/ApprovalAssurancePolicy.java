package ch.anass.keycloak.accessrequests.core.domain.approval;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;

import java.util.Objects;

/** Realm-wide assurance requirements for human approval, not grant lifetimes. */
public record ApprovalAssurancePolicy(Requirement high, Requirement critical) {

    public ApprovalAssurancePolicy {
        Objects.requireNonNull(high, "high must not be null");
        Objects.requireNonNull(critical, "critical must not be null");
        if (high.maxAgeSeconds() > 3600 || critical.maxAgeSeconds() > 300
                || critical.maxAgeSeconds() > high.maxAgeSeconds()
                || critical.loa() < high.loa()) {
            throw new IllegalArgumentException("Approval assurance policy exceeds its security limits");
        }
    }

    public static ApprovalAssurancePolicy defaults() {
        return new ApprovalAssurancePolicy(new Requirement("2", 2, 1800), new Requirement("2", 2, 300));
    }

    public Requirement requirementFor(RiskLevel riskLevel) {
        return switch (Objects.requireNonNull(riskLevel, "riskLevel must not be null")) {
            case LOW, MEDIUM -> null;
            case HIGH -> high;
            case CRITICAL -> critical;
        };
    }

    public record Requirement(String acr, int loa, int maxAgeSeconds) {
        public Requirement {
            if (acr == null || acr.isBlank() || acr.length() > 128 || !acr.equals(acr.trim())
                    || loa < 2 || loa > 10 || maxAgeSeconds < 1) {
                throw new IllegalArgumentException("Invalid approval assurance requirement");
            }
        }
    }
}
