package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.EntitlementAuditEvent;

public interface EntitlementAuditEventPublisher {

    void publish(EntitlementAuditEvent event);
}
