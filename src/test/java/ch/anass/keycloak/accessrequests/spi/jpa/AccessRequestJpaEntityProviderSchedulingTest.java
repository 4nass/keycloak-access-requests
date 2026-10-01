package ch.anass.keycloak.accessrequests.spi.jpa;

import ch.anass.keycloak.accessrequests.spi.notification.KeycloakAccessRequestNotificationOutboxDispatcher;
import ch.anass.keycloak.accessrequests.spi.provisioning.AccessPackageGrantExpirationDispatcher;
import org.junit.jupiter.api.Test;
import org.keycloak.timer.ScheduledTask;
import org.keycloak.timer.TimerProvider;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestJpaEntityProviderSchedulingTest {

    @Test
    void registersPackageExpirationAlongsideTheExistingNotificationOutbox() {
        List<Registration> registrations = new ArrayList<>();
        TimerProvider timer = (TimerProvider) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{TimerProvider.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("scheduleTask")) {
                        registrations.add(new Registration((ScheduledTask) arguments[0],
                                (long) arguments[1], (long) arguments[2], (String) arguments[3]));
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        AccessRequestJpaEntityProvider.scheduleTasks(timer);

        assertEquals(List.of(KeycloakAccessRequestNotificationOutboxDispatcher.TASK_NAME,
                        AccessPackageGrantExpirationDispatcher.TASK_NAME),
                registrations.stream().map(Registration::name).toList());
        assertEquals(List.of(5_000L, 300_000L),
                registrations.stream().map(Registration::intervalMillis).toList());
        for (Registration registration : registrations) {
            assertEquals(registration.name(), registration.task().getTaskName());
            assertTrue(registration.initialDelayMillis() > 0);
        }
    }

    private record Registration(ScheduledTask task, long initialDelayMillis, long intervalMillis, String name) {
    }
}
