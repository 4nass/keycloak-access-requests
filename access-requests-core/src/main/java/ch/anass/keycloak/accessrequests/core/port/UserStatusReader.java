package ch.anass.keycloak.accessrequests.core.port;

public interface UserStatusReader {

    boolean isEnabled(String realmId, String userId);
}
