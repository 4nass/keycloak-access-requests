package io.github.keycloakaccessrequests.core.domain;

import java.util.Objects;

public final class Entitlement {

    private final String id;
    private final String realmId;
    private final ResourceType resourceType;
    private final String resourceId;
    private final String displayName;
    private final boolean requestable;

    private Entitlement(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            boolean requestable) {
        this.id = requireText(id, "id");
        this.realmId = requireText(realmId, "realmId");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        this.resourceId = requireText(resourceId, "resourceId");
        this.displayName = requireText(displayName, "displayName");
        this.requestable = requestable;
    }

    public static Entitlement create(
            String id,
            String realmId,
            ResourceType resourceType,
            String resourceId,
            String displayName,
            boolean requestable) {
        return new Entitlement(id, realmId, resourceType, resourceId, displayName, requestable);
    }

    public String id() {
        return id;
    }

    public String realmId() {
        return realmId;
    }

    public ResourceType resourceType() {
        return resourceType;
    }

    public String resourceId() {
        return resourceId;
    }

    public String displayName() {
        return displayName;
    }

    public boolean requestable() {
        return requestable;
    }

    public Entitlement asNotRequestable() {
        return copy(id, realmId, false);
    }

    public Entitlement inRealm(String newRealmId) {
        return copy(id, newRealmId, requestable);
    }

    public Entitlement withId(String newId) {
        return copy(newId, realmId, requestable);
    }

    private Entitlement copy(String newId, String newRealmId, boolean newRequestable) {
        return new Entitlement(newId, newRealmId, resourceType, resourceId, displayName, newRequestable);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
