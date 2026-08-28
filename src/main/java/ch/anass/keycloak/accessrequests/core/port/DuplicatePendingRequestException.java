package ch.anass.keycloak.accessrequests.core.port;

public final class DuplicatePendingRequestException extends RuntimeException {

    public DuplicatePendingRequestException() {
        super("A pending access request already exists for this subject.");
    }
}
