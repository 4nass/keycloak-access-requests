package ch.anass.keycloak.accessrequests.core.service;

/** Removal failed; the original technical cause must only be logged server-side. */
public final class GrantRevocationRemovalException extends IllegalStateException {
    public GrantRevocationRemovalException(Throwable cause) {
        super("Package membership removal failed", cause);
    }
}
