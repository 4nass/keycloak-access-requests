package ch.anass.keycloak.accessrequests.spi.notification;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeycloakAccessRequestNotificationOutboxDispatcherTest {

    @Test
    void startsOneIsolatedDeliveryWorkflowForEachDueDelivery() {
        AtomicInteger transactions = new AtomicInteger();
        KeycloakAccessRequestNotificationOutboxDispatcher dispatcher =
                new KeycloakAccessRequestNotificationOutboxDispatcher(sessionFactory ->
                        transactions.incrementAndGet() <= 2);

        dispatcher.run(session());

        assertEquals(3, transactions.get());
    }

    @Test
    void capsOneTimerTickAtFiftyDueDeliveries() {
        AtomicInteger transactions = new AtomicInteger();
        KeycloakAccessRequestNotificationOutboxDispatcher dispatcher =
                new KeycloakAccessRequestNotificationOutboxDispatcher(sessionFactory -> {
                    transactions.incrementAndGet();
                    return true;
                });

        dispatcher.run(session());

        assertEquals(50, transactions.get());
    }

    private static KeycloakSession session() {
        KeycloakSessionFactory sessionFactory = proxy(KeycloakSessionFactory.class, (proxy, method, arguments) -> null);
        return proxy(KeycloakSession.class, (proxy, method, arguments) ->
                method.getName().equals("getKeycloakSessionFactory") ? sessionFactory : null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
