package ch.anass.keycloak.accessrequests.core.service;

public final class UserDisabledException extends RuntimeException {

    public UserDisabledException(String userId) {
        super("User is disabled: " + userId);
    }
}
