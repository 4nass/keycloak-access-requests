package ch.anass.keycloak.accessrequests.core.service;

/** Ownership cannot be established; never remove the mapping on this path. */
public final class GrantRevocationAuthorityException extends IllegalStateException {
    public GrantRevocationAuthorityException() {
        super("Package grant authority is no longer verifiable");
    }
}
