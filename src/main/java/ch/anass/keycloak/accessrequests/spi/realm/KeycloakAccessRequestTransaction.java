package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Participates in Keycloak's request transaction when one is already active.
 */
final class KeycloakAccessRequestTransaction implements AccessRequestTransaction {

    private final KeycloakTransactionManager transactionManager;

    KeycloakAccessRequestTransaction(KeycloakSession session) {
        this.transactionManager = Objects.requireNonNull(session, "session must not be null").getTransactionManager();
    }

    @Override
    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        boolean ownsTransaction = !transactionManager.isActive();
        if (ownsTransaction) {
            transactionManager.begin();
        }
        try {
            T result = operation.get();
            if (ownsTransaction) {
                transactionManager.commit();
            }
            return result;
        } catch (RuntimeException | Error exception) {
            if (ownsTransaction && transactionManager.isActive()) {
                transactionManager.rollback();
            } else if (!ownsTransaction) {
                transactionManager.setRollbackOnly();
            }
            throw exception;
        }
    }
}
