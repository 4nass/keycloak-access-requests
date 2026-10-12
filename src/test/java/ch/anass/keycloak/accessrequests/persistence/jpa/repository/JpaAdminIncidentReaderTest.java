package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationState;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAdminIncidentReader.IncidentState.ALL;
import static ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAdminIncidentReader.IncidentState.CLOSED;
import static ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAdminIncidentReader.IncidentState.OPEN;
import static ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAdminIncidentReader.IncidentType.NOTIFICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JpaAdminIncidentReaderTest {

    private static EntityManagerFactory factory;

    @BeforeAll
    static void startDatabase() {
        factory = Persistence.createEntityManagerFactory("access-requests-test");
    }

    @AfterAll
    static void stopDatabase() {
        factory.close();
    }

    @Test
    void combinesAllFailureSourcesWithGlobalOrderingPaginationAndRealmIsolation() {
        String realm = "incidents-" + UUID.randomUUID();
        String otherRealm = "other-" + UUID.randomUUID();
        Instant now = Instant.parse("2026-10-12T10:00:00Z");
        String notificationId = UUID.randomUUID().toString();
        String openRequestId = UUID.randomUUID().toString();
        String closedRequestId = UUID.randomUUID().toString();
        String revocationId = UUID.randomUUID().toString();
        try (EntityManager manager = factory.createEntityManager()) {
            manager.getTransaction().begin();
            try {
                addNotification(manager, realm, notificationId, now.plusSeconds(30));
                addNotification(manager, otherRealm, UUID.randomUUID().toString(), now.plusSeconds(60));
                addFailedRequest(manager, realm, openRequestId, now.plusSeconds(20), false);
                addFailedRequest(manager, realm, closedRequestId, now.plusSeconds(10), true);
                AccessGrant grant = new AccessGrant(revocationId, realm, "requester", "entitlement",
                        ResourceType.REALM_ROLE, "role", GrantOrigin.CREATED_BY_EXTENSION,
                        now.minusSeconds(300), now, GrantRevocationState.AUTHORIZED, 0, "jit-group");
                new JpaAccessGrantRepository(manager).create(grant);
                new JpaGrantRevocationFailureRepository(manager).record(
                        realm, revocationId, GrantRevocationFailureCode.REMOVAL_FAILED, now);
                manager.getTransaction().commit();
            } catch (RuntimeException exception) {
                manager.getTransaction().rollback();
                throw exception;
            }
            manager.clear();

            JpaAdminIncidentReader reader = new JpaAdminIncidentReader(manager);
            var first = reader.findPage(realm, JpaAdminIncidentReader.IncidentType.ALL, OPEN,
                    null, null, null, 2);
            assertEquals(List.of(notificationId, openRequestId),
                    first.items().stream().map(JpaAdminIncidentReader.IncidentItem::id).toList());
            var second = reader.findPage(realm, JpaAdminIncidentReader.IncidentType.ALL, OPEN,
                    null, null, first.nextCursor(), 2);
            assertEquals(List.of(revocationId),
                    second.items().stream().map(JpaAdminIncidentReader.IncidentItem::id).toList());
            assertEquals(null, second.nextCursor());
            assertEquals(List.of(closedRequestId),
                    reader.findPage(realm, JpaAdminIncidentReader.IncidentType.ALL, CLOSED,
                                    null, null, null, 10)
                            .items().stream().map(JpaAdminIncidentReader.IncidentItem::id).toList());
            assertEquals(4, reader.findPage(realm, JpaAdminIncidentReader.IncidentType.ALL, ALL,
                    null, null, null, 10).items().size());
            assertEquals(1, reader.findPage(realm, NOTIFICATION, ALL,
                    null, null, null, 10).items().size());
            assertEquals(0, reader.findPage(realm, NOTIFICATION, CLOSED,
                    null, null, null, 10).items().size());
            assertEquals(1, reader.findPage(otherRealm, NOTIFICATION, OPEN,
                    null, null, null, 10).items().size());
            assertEquals(List.of(notificationId, openRequestId),
                    reader.findPage(realm, JpaAdminIncidentReader.IncidentType.ALL, OPEN,
                                    now.plusSeconds(10).plusNanos(1), null, null, 10)
                            .items().stream().map(JpaAdminIncidentReader.IncidentItem::id).toList());
            assertEquals(0, reader.findPage(realm, NOTIFICATION, OPEN,
                    now.plusSeconds(30).plusNanos(1), null, null, 10).items().size());
            assertEquals(0, reader.findPage(realm, NOTIFICATION, OPEN,
                    null, now.plusSeconds(30).minusNanos(1), null, 10).items().size());
            assertThrows(IllegalArgumentException.class, () -> reader.findPage(realm,
                    JpaAdminIncidentReader.IncidentType.ALL, CLOSED, null, null, first.nextCursor(), 2));
            assertThrows(IllegalArgumentException.class, () -> reader.findPage(otherRealm,
                    JpaAdminIncidentReader.IncidentType.ALL, OPEN, null, null, first.nextCursor(), 2));
        }
    }

    @Test
    void usesIdAsStableTieBreakerForSameMillisecondIncidents() {
        String realm = "incident-ties-" + UUID.randomUUID();
        String firstId = "00000000-0000-0000-0000-000000000001";
        String secondId = "00000000-0000-0000-0000-000000000002";
        try (EntityManager manager = factory.createEntityManager()) {
            manager.getTransaction().begin();
            addNotification(manager, realm, secondId, Instant.parse("2026-10-12T10:00:00Z"));
            addNotification(manager, realm, firstId, Instant.parse("2026-10-12T10:00:00Z"));
            manager.getTransaction().commit();
            JpaAdminIncidentReader reader = new JpaAdminIncidentReader(manager);
            var first = reader.findPage(realm, NOTIFICATION, OPEN, null, null, null, 1);
            assertEquals(firstId, first.items().getFirst().id());
            assertEquals(secondId, reader.findPage(realm, NOTIFICATION, OPEN,
                    null, null, first.nextCursor(), 1).items().getFirst().id());
        }
    }

    @Test
    void rejectsUnboundedOrInvalidQueries() {
        try (EntityManager manager = factory.createEntityManager()) {
            JpaAdminIncidentReader reader = new JpaAdminIncidentReader(manager);
            assertThrows(IllegalArgumentException.class,
                    () -> reader.findPage("realm", NOTIFICATION, OPEN, null, null, null, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> reader.findPage("realm", NOTIFICATION, OPEN, null, null, null, 101));
            assertThrows(IllegalArgumentException.class,
                    () -> reader.findPage("realm", NOTIFICATION, OPEN, null, null, "invalid", 20));
            assertThrows(IllegalArgumentException.class,
                    () -> reader.findPage("", NOTIFICATION, OPEN, null, null, null, 20));
            assertThrows(IllegalArgumentException.class,
                    () -> reader.findPage("realm", NOTIFICATION, OPEN,
                            Instant.parse("2026-10-13T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"),
                            null, 20));
        }
    }

    private static void addNotification(EntityManager manager, String realm, String id, Instant attemptedAt) {
        manager.createNativeQuery("""
                insert into AR_NOTIFICATION_OUTBOX
                (ID, DELIVERY_KEY, EVENT_ID, REQUEST_ID, ENTITLEMENT_ID, REALM_ID, RECIPIENT_ID,
                 RECIPIENT_TYPE, NOTIFICATION_TYPE, STATE, ATTEMPT_COUNT, NEXT_ATTEMPT_TIMESTAMP,
                 LAST_ATTEMPT_TIMESTAMP, VERSION)
                values (:id, :deliveryKey, :eventId, :requestId, :entitlementId, :realmId, 'recipient',
                        'USER', 'REQUEST_SUBMITTED', 'FAILED', 10, :attemptedAt, :attemptedAt, 0)
                """)
                .setParameter("id", id)
                .setParameter("deliveryKey", id)
                .setParameter("eventId", UUID.randomUUID().toString())
                .setParameter("requestId", UUID.randomUUID().toString())
                .setParameter("entitlementId", UUID.randomUUID().toString())
                .setParameter("realmId", realm)
                .setParameter("attemptedAt", attemptedAt.toEpochMilli())
                .executeUpdate();
    }

    private static void addFailedRequest(EntityManager manager, String realm, String id, Instant failedAt,
            boolean closed) {
        AccessRequest request = AccessRequest.create(id, realm, "requester-" + id, id,
                ResourceType.REALM_ROLE, "role-" + id, "Role", "Needed for a project.",
                failedAt.minusSeconds(2));
        request.approve("approver", "Approved", failedAt.minusSeconds(1));
        request.markProvisioningFailed(failedAt);
        if (closed) request.closeFailedProvisioning("manager", "No longer required.", failedAt.plusSeconds(1));
        new JpaAccessRequestRepository(manager).createIfNoPending(request).orElseThrow();
    }
}
