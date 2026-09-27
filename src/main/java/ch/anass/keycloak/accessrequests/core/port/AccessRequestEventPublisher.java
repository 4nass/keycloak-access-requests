package ch.anass.keycloak.accessrequests.core.port;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;

public interface AccessRequestEventPublisher {

    void publish(AccessRequestEvent event);
}
