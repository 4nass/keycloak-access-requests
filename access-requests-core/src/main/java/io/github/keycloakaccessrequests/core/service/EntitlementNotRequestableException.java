package io.github.keycloakaccessrequests.core.service;

public final class EntitlementNotRequestableException extends RuntimeException {

    public EntitlementNotRequestableException(String entitlementId) {
        super("Entitlement is not requestable: " + entitlementId);
    }
}
