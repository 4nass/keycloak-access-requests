package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;

public interface AccessRequestEventPublisher {

    void publish(AccessRequestEvent event);
}
