package ch.anass.keycloak.accessrequests.core.port;

import java.util.function.Supplier;

@FunctionalInterface
public interface AccessRequestTransaction {

    /**
     * Executes an aggregate update and its audit publication atomically.
     * Implementations must roll back both when the operation fails.
     */
    <T> T execute(Supplier<T> operation);
}
