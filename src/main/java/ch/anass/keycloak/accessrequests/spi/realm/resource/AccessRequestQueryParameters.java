package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;

import java.time.Instant;
import java.time.format.DateTimeParseException;

final class AccessRequestQueryParameters {

    private AccessRequestQueryParameters() {
    }

    static DecisionStatus parseDecisionStatus(String value) {
        return parseEnum(DecisionStatus.class, value, "status");
    }

    static ResourceType parseResourceType(String value) {
        return parseEnum(ResourceType.class, value, "resourceType");
    }

    static <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, String parameter) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(parameter + " is invalid", exception);
        }
    }

    static Instant parseInstant(String value, String parameter) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(parameter + " must be an ISO-8601 instant", exception);
        }
    }
}
