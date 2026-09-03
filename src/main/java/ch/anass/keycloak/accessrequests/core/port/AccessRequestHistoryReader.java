package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;

import java.util.List;

/**
 * Reads the immutable audit history for an access request within a realm.
 */
public interface AccessRequestHistoryReader {

    List<AccessRequestEvent> findByRequestId(String realmId, String requestId);
}
