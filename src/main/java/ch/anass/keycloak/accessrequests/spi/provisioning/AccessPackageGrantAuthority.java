package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationAuthority;
import ch.anass.keycloak.accessrequests.core.port.AccessPackageRepository;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/** Confirms that a temporary package grant still belongs to the extension. */
public final class AccessPackageGrantAuthority implements AccessGrantRevocationAuthority {

    private final AccessPackageGrantVerifier verifier;

    public AccessPackageGrantAuthority(KeycloakSession session, RealmModel realm, AccessPackageRepository packages) {
        verifier = new AccessPackageGrantVerifier(session, realm, packages);
    }

    @Override
    public boolean isExclusivelyManaged(AccessGrant grant) {
        return verifier.resolve(grant).isPresent();
    }
}
