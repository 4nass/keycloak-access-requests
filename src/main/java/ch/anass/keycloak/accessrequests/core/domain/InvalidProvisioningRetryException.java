package ch.anass.keycloak.accessrequests.core.domain;

public final class InvalidProvisioningRetryException extends RuntimeException {

    public InvalidProvisioningRetryException() {
        super("Only approved requests with failed provisioning can be retried.");
    }
}
