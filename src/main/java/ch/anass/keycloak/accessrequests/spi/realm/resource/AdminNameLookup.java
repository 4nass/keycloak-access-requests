package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;

/** Presentation-only names for realm-scoped administration responses. */
interface AdminNameLookup {
    String user(String id);

    String entitlement(String id, String snapshot);

    String request(String id);

    String role(String id);

    String resource(ResourceType type, String id);
}
