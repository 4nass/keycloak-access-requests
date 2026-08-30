package ch.anass.keycloak.accessrequests.spi.realm;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakAccessRequestTransactionTest {

    @Test
    void beginsAndCommitsWhenNoKeycloakTransactionIsActive() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(false);

        String result = transaction(transactionManager).execute(() -> "created");

        assertEquals("created", result);
        assertEquals(List.of("begin", "commit"), transactionManager.events());
        assertFalse(transactionManager.isActive());
        assertFalse(transactionManager.isRollbackOnly());
    }

    @Test
    void rollsBackItsOwnTransactionWhenTheOperationFails() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(false);

        assertThrows(IllegalStateException.class,
                () -> transaction(transactionManager).execute(() -> {
                    throw new IllegalStateException("Audit publication failed");
                }));

        assertEquals(List.of("begin", "rollback"), transactionManager.events());
        assertFalse(transactionManager.isActive());
    }

    @Test
    void joinsAnActiveKeycloakTransactionWithoutCommittingIt() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(true);

        String result = transaction(transactionManager).execute(() -> "created");

        assertEquals("created", result);
        assertTrue(transactionManager.events().isEmpty());
        assertTrue(transactionManager.isActive());
        assertFalse(transactionManager.isRollbackOnly());
    }

    @Test
    void marksAnActiveKeycloakTransactionForRollbackWhenTheOperationFails() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(true);

        assertThrows(IllegalStateException.class,
                () -> transaction(transactionManager).execute(() -> {
                    throw new IllegalStateException("Audit publication failed");
                }));

        assertEquals(List.of("setRollbackOnly"), transactionManager.events());
        assertTrue(transactionManager.isActive());
        assertTrue(transactionManager.isRollbackOnly());
    }

    private static KeycloakAccessRequestTransaction transaction(RecordingTransactionManager transactionManager) {
        KeycloakSession session = proxy(KeycloakSession.class, (proxy, method, arguments) ->
                method.getName().equals("getTransactionManager") ? transactionManager.proxy() : null);
        return new KeycloakAccessRequestTransaction(session);
    }

    private static final class RecordingTransactionManager {

        private final List<String> events = new ArrayList<>();
        private boolean active;
        private boolean rollbackOnly;

        private RecordingTransactionManager(boolean active) {
            this.active = active;
        }

        private KeycloakTransactionManager proxy() {
            return KeycloakAccessRequestTransactionTest.proxy(
                    KeycloakTransactionManager.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "begin" -> {
                    events.add("begin");
                    active = true;
                    yield null;
                }
                case "commit" -> {
                    events.add("commit");
                    active = false;
                    yield null;
                }
                case "rollback" -> {
                    events.add("rollback");
                    active = false;
                    yield null;
                }
                case "setRollbackOnly" -> {
                    events.add("setRollbackOnly");
                    rollbackOnly = true;
                    yield null;
                }
                case "getRollbackOnly" -> rollbackOnly;
                case "isActive" -> active;
                default -> null;
                    });
        }

        private List<String> events() {
            return List.copyOf(events);
        }

        private boolean isActive() {
            return active;
        }

        private boolean isRollbackOnly() {
            return rollbackOnly;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
