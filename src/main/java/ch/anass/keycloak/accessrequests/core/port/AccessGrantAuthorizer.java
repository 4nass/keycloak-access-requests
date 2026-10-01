package ch.anass.keycloak.accessrequests.core.port;

/** Verifies and authorizes a newly provisioned grant within the current transaction. */
@FunctionalInterface
public interface AccessGrantAuthorizer {

    void authorize(String realmId, String requestId);
}
