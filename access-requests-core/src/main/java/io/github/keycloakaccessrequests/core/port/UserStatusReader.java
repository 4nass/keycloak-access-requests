package io.github.keycloakaccessrequests.core.port;

public interface UserStatusReader {

    boolean isEnabled(String realmId, String userId);
}
