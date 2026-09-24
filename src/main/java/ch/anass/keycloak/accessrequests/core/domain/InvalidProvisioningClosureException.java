package ch.anass.keycloak.accessrequests.core.domain;

public final class InvalidProvisioningClosureException extends RuntimeException {
    public InvalidProvisioningClosureException() {
        super("Only an open, approved request with failed provisioning can be closed.");
    }
}
