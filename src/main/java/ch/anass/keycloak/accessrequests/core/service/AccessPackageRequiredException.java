package ch.anass.keycloak.accessrequests.core.service;

/** A request cannot be fulfilled safely without its dedicated delivery group. */
public final class AccessPackageRequiredException extends RuntimeException {

    public AccessPackageRequiredException() {
        super("A requestable entitlement must be bound to an access package");
    }
}
