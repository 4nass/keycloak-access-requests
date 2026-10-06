package ch.anass.keycloak.accessrequests.spi.realm.assurance;

import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalAssurancePolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;

public final class RealmApprovalAssurancePolicy {

    public static final String ATTRIBUTE = "accessRequests.approvalAssurancePolicy";

    public ApprovalAssurancePolicy read(RealmModel realm) {
        String value = realm.getAttribute(ATTRIBUTE);
        if (value == null) {
            return ApprovalAssurancePolicy.defaults();
        }
        try {
            ApprovalAssurancePolicy policy = JsonSerialization.readValue(value, ApprovalAssurancePolicy.class);
            if (policy == null) {
                throw new IllegalArgumentException("Approval assurance policy must not be null");
            }
            return policy;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ApprovalAssuranceException("ASSURANCE_POLICY_INVALID", null);
        }
    }

    public void write(RealmModel realm, ApprovalAssurancePolicy policy) {
        try {
            realm.setAttribute(ATTRIBUTE, JsonSerialization.writeValueAsString(policy));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not serialize approval assurance policy", exception);
        }
    }
}
