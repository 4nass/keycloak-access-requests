package ch.anass.keycloak.accessrequests.core.port;

public interface ApprovalAuthorizer {

    boolean canDecide(String realmId, String actorId, String entitlementId);
}
