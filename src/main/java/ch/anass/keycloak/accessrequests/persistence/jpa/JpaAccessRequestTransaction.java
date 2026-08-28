package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import jakarta.persistence.EntityManager;

import java.util.Objects;
import java.util.function.Supplier;

public final class JpaAccessRequestTransaction implements AccessRequestTransaction {

    private final EntityManager entityManager;

    public JpaAccessRequestTransaction(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    public <T> T execute(Supplier<T> operation) {
        var transaction = entityManager.getTransaction();
        transaction.begin();
        try {
            T result = operation.get();
            transaction.commit();
            return result;
        } catch (RuntimeException | Error exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw exception;
        }
    }
}
