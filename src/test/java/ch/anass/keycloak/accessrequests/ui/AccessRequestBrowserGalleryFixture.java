package ch.anass.keycloak.accessrequests.ui;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Populates the disposable browser-test database with operational states that cannot be reached
 * reliably during a browser run (exhausted email retries and a deferred revocation failure).
 * The packaged provider serves every assertion and optional screenshot through its real admin endpoints.
 */
final class AccessRequestBrowserGalleryFixture {

    record Incidents(String openProvisioningRequestId, String closedProvisioningRequestId, String deliveryGroupId) {
    }

    private AccessRequestBrowserGalleryFixture() {
    }

    static Incidents seed(PostgreSQLContainer postgres, String approvedRequestId,
            String openRequesterId, String closedRequesterId, String approverId, String approverRoleId)
            throws SQLException {
        String openId = UUID.randomUUID().toString();
        String closedId = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        String deliveryGroupId;
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try {
                RequestReferences references = references(connection, approvedRequestId);
                deliveryGroupId = references.deliveryGroupId();
                insertFailedRequest(connection, approvedRequestId, openId, openRequesterId, approverId,
                        now, false);
                insertFailedRequest(connection, approvedRequestId, closedId, closedRequesterId, approverId,
                        now, true);
                insertHistory(connection, openId, openRequesterId, approverId, now, false);
                insertHistory(connection, closedId, closedRequesterId, approverId, now, true);

                insertDelivery(connection, references, openId, openRequesterId, "USER",
                        "PROVISIONING_FAILED", "FAILED", 10, now);
                insertDelivery(connection, references, closedId, approverRoleId, "REALM_ROLE",
                        "PROVISIONING_CLOSED", "FAILED", 10, now);
                insertDelivery(connection, references, approvedRequestId, openRequesterId, "USER",
                        "REQUEST_SUBMITTED", "PENDING", 0, now);
                insertDelivery(connection, references, approvedRequestId, openRequesterId, "USER",
                        "REQUEST_APPROVED", "PROCESSING", 1, now);
                insertDelivery(connection, references, approvedRequestId, openRequesterId, "USER",
                        "REQUEST_APPROVED", "DELIVERED", 1, now);
                insertDelivery(connection, references, approvedRequestId, openRequesterId, "USER",
                        "REQUEST_REJECTED", "DISCARDED", 1, now);

                try (PreparedStatement expire = connection.prepareStatement(
                        "update AR_ACCESS_GRANT set EXPIRES_TIMESTAMP = RECORDED_TIMESTAMP + 1000 "
                                + "where REQUEST_ID = ? and RECORDED_TIMESTAMP + 1000 < ?")) {
                    expire.setString(1, approvedRequestId);
                    expire.setLong(2, now);
                    assertEquals(1, expire.executeUpdate(), "The approved request must have a package grant");
                }
                try (PreparedStatement failure = connection.prepareStatement("""
                        insert into AR_GRANT_REVOCATION_FAILURE
                            (REQUEST_ID, REALM_ID, FAILURE_CODE, ATTEMPT_COUNT,
                             FIRST_FAILED_TIMESTAMP, LAST_FAILED_TIMESTAMP, NEXT_ATTEMPT_TIMESTAMP)
                        values (?, ?, 'AUTHORITY_UNVERIFIABLE', 3, ?, ?, ?)
                        """)) {
                    failure.setString(1, approvedRequestId);
                    failure.setString(2, references.realmId());
                    failure.setLong(3, now - Duration.ofMinutes(15).toMillis());
                    failure.setLong(4, now - Duration.ofMinutes(2).toMillis());
                    // Keep the scheduler from consuming the illustrative incident during the browser run.
                    failure.setLong(5, now + Duration.ofDays(1).toMillis());
                    assertEquals(1, failure.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return new Incidents(openId, closedId, deliveryGroupId);
    }

    private static RequestReferences references(Connection connection, String requestId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select r.REALM_ID, r.ENTITLEMENT_ID, g.DELIVERY_GROUP_ID "
                        + "from AR_ACCESS_REQUEST r join AR_ACCESS_GRANT g on g.REQUEST_ID = r.ID "
                        + "where r.ID = ?")) {
            query.setString(1, requestId);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next(), "The browser-created request must exist before seeding the gallery");
                return new RequestReferences(result.getString(1), result.getString(2), result.getString(3));
            }
        }
    }

    private static void insertFailedRequest(Connection connection, String sourceId, String id,
            String requesterId, String approverId, long now, boolean closed) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into AR_ACCESS_REQUEST
                    (ID, REALM_ID, REQUESTER_ID, ENTITLEMENT_ID, RESOURCE_TYPE, RESOURCE_ID,
                     RESOURCE_NAME_SNAPSHOT, JUSTIFICATION, DECISION_STATUS, PROVISIONING_STATUS,
                     APPROVER_ID, DECISION_COMMENT, CREATED_TIMESTAMP, UPDATED_TIMESTAMP,
                     DECIDED_TIMESTAMP, PROVISIONING_CLOSED_TIMESTAMP, PROVISIONING_CLOSED_BY,
                     PROVISIONING_CLOSURE_REASON, VERSION, REQUESTED_DURATION_SECONDS, PERMANENT)
                select ?, REALM_ID, ?, ENTITLEMENT_ID, RESOURCE_TYPE, RESOURCE_ID,
                       RESOURCE_NAME_SNAPSHOT, ?, 'APPROVED', 'FAILED', ?, ?, ?, ?, ?, ?, ?, ?, 3,
                       REQUESTED_DURATION_SECONDS, PERMANENT
                  from AR_ACCESS_REQUEST where ID = ?
                """)) {
            insert.setString(1, id);
            insert.setString(2, requesterId);
            insert.setString(3, closed
                    ? "The access is no longer available; preserve the incident for audit."
                    : "Need reporting access for a time-sensitive project.");
            insert.setString(4, approverId);
            insert.setString(5, "Approved for the project.");
            insert.setLong(6, now - Duration.ofMinutes(20).toMillis());
            insert.setLong(7, now - Duration.ofMinutes(1).toMillis());
            insert.setLong(8, now - Duration.ofMinutes(18).toMillis());
            if (closed) {
                insert.setLong(9, now - Duration.ofMinutes(1).toMillis());
            } else {
                insert.setNull(9, Types.BIGINT);
            }
            insert.setString(10, closed ? approverId : null);
            insert.setString(11, closed ? "The source role was retired and cannot be restored." : null);
            insert.setString(12, sourceId);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertHistory(Connection connection, String requestId, String requesterId,
            String approverId, long now, boolean closed) throws SQLException {
        insertEvent(connection, requestId, "REQUEST_CREATED", requesterId, now - 20 * 60_000L,
                0, null, null);
        insertEvent(connection, requestId, "REQUEST_APPROVED", approverId, now - 18 * 60_000L,
                1, "Approved for the project.", null);
        insertEvent(connection, requestId, "PROVISIONING_STARTED", approverId, now - 17 * 60_000L,
                2, null, null);
        insertEvent(connection, requestId, "PROVISIONING_FAILED", approverId, now - 16 * 60_000L,
                3, null, closed ? "RESOURCE_MISSING" : "PROVIDER_UNAVAILABLE");
        if (closed) {
            insertEvent(connection, requestId, "PROVISIONING_CLOSED", approverId,
                    now - 60_000L, 4, "The source role was retired and cannot be restored.", null);
        }
    }

    private static void insertEvent(Connection connection, String requestId, String type, String actorId,
            long timestamp, long version, String comment, String metadata) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into AR_ACCESS_REQUEST_HISTORY
                    (ID, REQUEST_ID, REALM_ID, EVENT_TYPE, ACTOR_ID, EVENT_TIMESTAMP,
                     REQUEST_VERSION, COMMENT, METADATA)
                select ?, ?, REALM_ID, ?, ?, ?, ?, ?, ?
                  from AR_ACCESS_REQUEST where ID = ?
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, requestId);
            insert.setString(3, type);
            insert.setString(4, actorId);
            insert.setLong(5, timestamp);
            insert.setLong(6, version);
            insert.setString(7, comment);
            insert.setString(8, metadata);
            insert.setString(9, requestId);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void insertDelivery(Connection connection, RequestReferences references, String requestId,
            String recipientId, String recipientType, String notificationType, String state,
            int attempts, long now) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into AR_NOTIFICATION_OUTBOX
                    (ID, DELIVERY_KEY, EVENT_ID, REQUEST_ID, ENTITLEMENT_ID, REALM_ID,
                     RECIPIENT_ID, RECIPIENT_TYPE, NOTIFICATION_TYPE, STATE,
                     ATTEMPT_COUNT, NEXT_ATTEMPT_TIMESTAMP, LAST_ATTEMPT_TIMESTAMP,
                     LEASE_UNTIL_TIMESTAMP, DELIVERED_TIMESTAMP, VERSION)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """)) {
            insert.setString(1, id);
            insert.setString(2, "gallery-" + id);
            insert.setString(3, UUID.randomUUID().toString());
            insert.setString(4, requestId);
            insert.setString(5, references.entitlementId());
            insert.setString(6, references.realmId());
            insert.setString(7, recipientId);
            insert.setString(8, recipientType);
            insert.setString(9, notificationType);
            insert.setString(10, state);
            insert.setInt(11, attempts);
            insert.setLong(12, now + Duration.ofDays(1).toMillis());
            if (attempts == 0) {
                insert.setNull(13, Types.BIGINT);
            } else {
                insert.setLong(13, now - Duration.ofMinutes(2).toMillis());
            }
            if ("PROCESSING".equals(state)) {
                insert.setLong(14, now + Duration.ofDays(1).toMillis());
            } else {
                insert.setNull(14, Types.BIGINT);
            }
            if ("DELIVERED".equals(state) || "DISCARDED".equals(state)) {
                insert.setLong(15, now - Duration.ofMinutes(1).toMillis());
            } else {
                insert.setNull(15, Types.BIGINT);
            }
            assertEquals(1, insert.executeUpdate());
        }
    }

    private record RequestReferences(String realmId, String entitlementId, String deliveryGroupId) {
    }
}
