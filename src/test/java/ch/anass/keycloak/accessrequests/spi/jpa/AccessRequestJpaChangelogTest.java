package ch.anass.keycloak.accessrequests.spi.jpa;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestJpaChangelogTest {

    private static final String CHANGELOG_LOCATION = "META-INF/access-requests-changelog.xml";

    @Test
    void createsTheCompleteSchemaWithAnIdempotentDurationMigration() throws Exception {
        String databaseUrl = databaseUrl();

        applyChangelog(databaseUrl);
        applyChangelog(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            assertEquals("2", valueOf(connection, "select count(*) from DATABASECHANGELOG"));
            assertEquals(Set.of("initial-schema", "entitlement-duration-policy"),
                    changelogIds(connection));
            assertEquals(
                    Set.of(
                            "ID",
                            "REALM_ID",
                            "REQUESTER_ID",
                            "ENTITLEMENT_ID",
                            "RESOURCE_TYPE",
                            "RESOURCE_ID",
                            "RESOURCE_NAME_SNAPSHOT",
                            "JUSTIFICATION",
                            "REQUESTED_DURATION_SECONDS",
                            "PERMANENT",
                            "DECISION_STATUS",
                            "PROVISIONING_STATUS",
                            "APPROVER_ID",
                            "DECISION_COMMENT",
                            "CREATED_TIMESTAMP",
                            "UPDATED_TIMESTAMP",
                            "DECIDED_TIMESTAMP",
                            "PROVISIONING_CLOSED_TIMESTAMP",
                            "PROVISIONING_CLOSED_BY",
                            "PROVISIONING_CLOSURE_REASON",
                            "VERSION"),
                    columnsOf(connection, "AR_ACCESS_REQUEST"));
            assertEquals(
                    Set.of(
                            "ID",
                            "REQUEST_ID",
                            "REALM_ID",
                            "EVENT_TYPE",
                            "ACTOR_ID",
                            "EVENT_TIMESTAMP",
                            "REQUEST_VERSION",
                            "REVOCATION_ATTEMPT",
                            "COMMENT",
                            "METADATA",
                            "ASSURANCE_REQUIRED_ACR",
                            "ASSURANCE_REQUIRED_LOA",
                            "ASSURANCE_MAX_AGE_SECONDS",
                            "ASSURANCE_OBSERVED_ACR",
                            "ASSURANCE_OBSERVED_LOA",
                            "ASSURANCE_AUTHENTICATED_TIMESTAMP",
                            "ASSURANCE_TOKEN_ISSUED_TIMESTAMP",
                            "ASSURANCE_VERIFIED_TIMESTAMP"),
                    columnsOf(connection, "AR_ACCESS_REQUEST_HISTORY"));
            assertEquals(
                    Set.of(
                            "ID",
                            "REALM_ID",
                            "RESOURCE_TYPE",
                            "RESOURCE_ID",
                            "DISPLAY_NAME",
                            "DESCRIPTION",
                            "RISK_LEVEL",
                            "APPROVER_ROLE_ID",
                            "REQUESTABLE",
                            "AUTO_APPROVE",
                            "DEFAULT_DURATION_SECONDS",
                            "MAX_DURATION_SECONDS",
                            "ALLOW_PERMANENT",
                            "CREATED_TIMESTAMP",
                            "UPDATED_TIMESTAMP",
                            "VERSION"),
                    columnsOf(connection, "AR_ENTITLEMENT"));
            assertEquals(
                    Set.of(
                            "ID",
                            "ENTITLEMENT_ID",
                            "REALM_ID",
                            "EVENT_TYPE",
                            "ACTOR_ID",
                            "EVENT_TIMESTAMP",
                            "RESOURCE_TYPE",
                            "RESOURCE_ID",
                            "DISPLAY_NAME",
                            "DESCRIPTION",
                            "RISK_LEVEL",
                            "APPROVER_ROLE_ID",
                            "REQUESTABLE",
                            "AUTO_APPROVE",
                            "DEFAULT_DURATION_SECONDS",
                            "MAX_DURATION_SECONDS",
                            "ALLOW_PERMANENT",
                            "ROLE_MAPPINGS_BEFORE",
                            "ROLE_MAPPINGS_AFTER",
                            "VERSION"),
                    columnsOf(connection, "AR_ENTITLEMENT_HISTORY"));
            assertEquals(
                    Set.of(
                            "ID",
                            "DELIVERY_KEY",
                            "EVENT_ID",
                            "REQUEST_ID",
                            "ENTITLEMENT_ID",
                            "REALM_ID",
                            "RECIPIENT_ID",
                            "RECIPIENT_TYPE",
                            "NOTIFICATION_TYPE",
                            "STATE",
                            "ATTEMPT_COUNT",
                            "NEXT_ATTEMPT_TIMESTAMP",
                            "LAST_ATTEMPT_TIMESTAMP",
                            "LEASE_UNTIL_TIMESTAMP",
                            "PROCESSOR_ID",
                            "DELIVERED_TIMESTAMP",
                            "VERSION"),
                    columnsOf(connection, "AR_NOTIFICATION_OUTBOX"));
            assertEquals(Set.of(
                            "REQUEST_ID", "REALM_ID", "REQUESTER_ID", "ENTITLEMENT_ID",
                            "RESOURCE_TYPE", "RESOURCE_ID", "DELIVERY_GROUP_ID", "GRANT_ORIGIN", "RECORDED_TIMESTAMP",
                            "EXPIRES_TIMESTAMP",
                            "REVOCATION_STATE", "VERSION"),
                    columnsOf(connection, "AR_ACCESS_GRANT"));
            assertEquals(Set.of("ENTITLEMENT_ID", "REALM_ID", "GROUP_ID", "GROUP_NAME"),
                    columnsOf(connection, "AR_ACCESS_PACKAGE"));
            assertEquals(Set.of("ENTITLEMENT_ID", "MAPPING_ORDER", "ROLE_TYPE", "ROLE_ID"),
                    columnsOf(connection, "AR_ACCESS_PACKAGE_ROLE"));
            assertTrue(indexNamesOf(connection, "AR_ACCESS_REQUEST")
                    .contains("IDX_ACCESS_REQUEST_REQUESTER_CREATED"));
            assertTrue(indexNamesOf(connection, "AR_ACCESS_REQUEST")
                    .contains("IDX_ACCESS_REQUEST_APPROVAL_QUEUE"));
            assertEquals(java.util.List.of("REALM_ID", "ENTITLEMENT_ID"),
                    indexColumnsOf(connection, "AR_ACCESS_REQUEST", "IDX_ACCESS_REQUEST_REALM_ENTITLEMENT"));
            assertTrue(indexNamesOf(connection, "AR_ACCESS_REQUEST_HISTORY")
                    .contains("IDX_ACCESS_REQUEST_HISTORY_FAILURE"));
            assertTrue(indexNamesOf(connection, "AR_ACCESS_REQUEST_HISTORY")
                    .containsAll(java.util.List.of(
                            "IDX_ACCESS_REQUEST_HISTORY_REALM_TIME",
                            "IDX_ACCESS_REQUEST_HISTORY_TYPE_TIME",
                            "IDX_ACCESS_REQUEST_HISTORY_ACTOR_TIME")));
            assertTrue(indexNamesOf(connection, "AR_ENTITLEMENT_HISTORY")
                    .contains("IDX_ENTITLEMENT_HISTORY_ENTITLEMENT_TIME"));
            assertTrue(indexNamesOf(connection, "AR_NOTIFICATION_OUTBOX")
                    .contains("IDX_NOTIFICATION_OUTBOX_DUE"));
            assertTrue(indexNamesOf(connection, "AR_NOTIFICATION_OUTBOX")
                    .contains("IDX_NOTIFICATION_OUTBOX_REALM_STATE_ATTEMPT"));
            assertTrue(indexNamesOf(connection, "AR_ACCESS_GRANT")
                    .contains("IDX_ACCESS_GRANT_RESOURCE"));
            assertEquals(java.util.List.of("RESOLVED_TIMESTAMP", "NEXT_ATTEMPT_TIMESTAMP", "REQUEST_ID"),
                    indexColumnsOf(connection, "AR_GRANT_REVOCATION_FAILURE",
                            "IDX_GRANT_REVOCATION_FAILURE_RETRY"));
        }
    }

    @Test
    void enforcesOneStoredGroupPerEntitlementAndPreventsGroupIdReuse() throws Exception {
        String databaseUrl = databaseUrl();
        applyChangelog(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            insertJitEntitlement(connection, "entitlement-1", "realm-1");
            insertJitEntitlement(connection, "entitlement-2", "realm-2");
            insertJitEntitlement(connection, "entitlement-3", "realm-1");
            insertAccessPackage(connection, "entitlement-1", "realm-1", "group-1", "AR_PKG_one");

            assertThrows(SQLException.class, () -> insertAccessPackage(connection,
                    "entitlement-1", "realm-1", "group-2", "AR_PKG_two"));
            assertThrows(SQLException.class, () -> insertAccessPackage(connection,
                    "entitlement-2", "realm-2", "group-1", "AR_PKG_two"));
            assertThrows(SQLException.class, () -> insertAccessPackage(connection,
                    "entitlement-3", "realm-1", "group-3", "AR_PKG_one"));
            assertThrows(SQLException.class, () -> insertAccessPackage(connection,
                    "missing", "realm-1", "group-3", "AR_PKG_missing"));
        }
    }

    @Test
    void preventsDuplicateRoleMappingsWithinAPackage() throws Exception {
        String databaseUrl = databaseUrl();
        applyChangelog(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            insertJitEntitlement(connection, "entitlement-1", "realm-1");
            insertAccessPackage(connection, "entitlement-1", "realm-1", "group-1", "AR_PKG_one");
            connection.createStatement().executeUpdate("""
                    insert into AR_ACCESS_PACKAGE_ROLE (ENTITLEMENT_ID, MAPPING_ORDER, ROLE_TYPE, ROLE_ID)
                    values ('entitlement-1', 0, 'REALM_ROLE', 'role-1')
                    """);

            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate("""
                    insert into AR_ACCESS_PACKAGE_ROLE (ENTITLEMENT_ID, MAPPING_ORDER, ROLE_TYPE, ROLE_ID)
                    values ('entitlement-1', 1, 'REALM_ROLE', 'role-1')
                    """));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate("""
                    insert into AR_ACCESS_PACKAGE_ROLE (ENTITLEMENT_ID, MAPPING_ORDER, ROLE_TYPE, ROLE_ID)
                    values ('missing', 0, 'REALM_ROLE', 'role-2')
                    """));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate("""
                    insert into AR_ACCESS_PACKAGE_ROLE (ENTITLEMENT_ID, MAPPING_ORDER, ROLE_TYPE, ROLE_ID)
                    values ('entitlement-1', 2, 'GROUP', 'source-group')
                    """));
        }
    }

    @Test
    void preventsDuplicatePendingRequestsForTheSameRequesterAndEntitlement() throws Exception {
        String databaseUrl = databaseUrl();
        applyChangelog(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            insertPendingRequest(connection, "request-1");

            assertThrows(SQLException.class, () -> insertPendingRequest(connection, "request-2"));
        }
    }

    @Test
    void backfillsExistingEntitlementsAndHistoryWithoutEnablingPermanentAccess() throws Exception {
        String databaseUrl = databaseUrl();
        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG_LOCATION,
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(1, new Contexts(), new LabelExpression());
            }
        }
        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            connection.createStatement().executeUpdate("""
                    insert into AR_ENTITLEMENT (
                        ID, REALM_ID, RESOURCE_TYPE, RESOURCE_ID, DISPLAY_NAME, DESCRIPTION,
                        RISK_LEVEL, APPROVER_ROLE_ID, REQUESTABLE, CREATED_TIMESTAMP,
                        UPDATED_TIMESTAMP, VERSION)
                    values ('old-low', 'realm-1', 'REALM_ROLE', 'role-low', 'Low access',
                            'Legacy access.', 'LOW', 'approver', TRUE, 1, 1, 0)
                    """);
            insertPendingRequest(connection, "old-request");
            connection.createStatement().executeUpdate("""
                    insert into AR_ENTITLEMENT_HISTORY (
                        ID, ENTITLEMENT_ID, REALM_ID, EVENT_TYPE, ACTOR_ID, EVENT_TIMESTAMP,
                        RESOURCE_TYPE, RESOURCE_ID, DISPLAY_NAME, DESCRIPTION, RISK_LEVEL,
                        APPROVER_ROLE_ID, REQUESTABLE, VERSION)
                    values ('old-history', 'old-low', 'realm-1', 'ENTITLEMENT_CREATED',
                            'admin', 1, 'REALM_ROLE', 'role-low', 'Low access', 'Legacy access.',
                            'MEDIUM', 'approver', TRUE, 0)
                    """);
        }

        applyChangelog(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            assertEquals("2592000", valueOf(connection,
                    "select DEFAULT_DURATION_SECONDS from AR_ENTITLEMENT where ID = 'old-low'"));
            assertEquals("7776000", valueOf(connection,
                    "select MAX_DURATION_SECONDS from AR_ENTITLEMENT where ID = 'old-low'"));
            assertEquals("FALSE", valueOf(connection,
                    "select ALLOW_PERMANENT from AR_ENTITLEMENT where ID = 'old-low'").toUpperCase(java.util.Locale.ROOT));
            assertEquals("604800", valueOf(connection,
                    "select DEFAULT_DURATION_SECONDS from AR_ENTITLEMENT_HISTORY where ID = 'old-history'"));
            assertEquals("2592000", valueOf(connection,
                    "select MAX_DURATION_SECONDS from AR_ENTITLEMENT_HISTORY where ID = 'old-history'"));
            assertEquals("FALSE", valueOf(connection,
                    "select ALLOW_PERMANENT from AR_ENTITLEMENT_HISTORY where ID = 'old-history'")
                    .toUpperCase(java.util.Locale.ROOT));
            assertEquals("FALSE", valueOf(connection,
                    "select PERMANENT from AR_ACCESS_REQUEST where ID = 'old-request'")
                    .toUpperCase(java.util.Locale.ROOT));
            assertEquals(null, valueOf(connection,
                    "select REQUESTED_DURATION_SECONDS from AR_ACCESS_REQUEST where ID = 'old-request'"));
        }
    }

    @Test
    void defaultsNewGrantRowsToUnverifiedRevocationState() throws Exception {
        String databaseUrl = databaseUrl();
        applyChangelog(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into AR_ACCESS_GRANT (
                        REQUEST_ID, REALM_ID, REQUESTER_ID, ENTITLEMENT_ID,
                        RESOURCE_TYPE, RESOURCE_ID, GRANT_ORIGIN, RECORDED_TIMESTAMP)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, "request-1");
                statement.setString(2, "realm-1");
                statement.setString(3, "user-1");
                statement.setString(4, "entitlement-1");
                statement.setString(5, "REALM_ROLE");
                statement.setString(6, "role-1");
                statement.setString(7, "CREATED_BY_EXTENSION");
                statement.setLong(8, 1_700_000_000_000L);
                statement.executeUpdate();
            }

            assertEquals("UNVERIFIED", valueOf(connection,
                    "select REVOCATION_STATE from AR_ACCESS_GRANT where REQUEST_ID = 'request-1'"));
            assertEquals("0", valueOf(connection,
                    "select VERSION from AR_ACCESS_GRANT where REQUEST_ID = 'request-1'"));
        }
    }

    @Test
    void storesDecisionAndAuditCommentsLongerThanTwoThousandCharacters() throws Exception {
        String databaseUrl = databaseUrl();
        String longComment = "x".repeat(4_000);
        applyChangelog(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            insertRequest(connection, "request-1", longComment);
            insertAuditEvent(connection, "event-1", "request-1", longComment);

            assertEquals(longComment, valueOf(connection,
                    "select DECISION_COMMENT from AR_ACCESS_REQUEST where ID = 'request-1'"));
            assertEquals(longComment, valueOf(connection,
                    "select COMMENT from AR_ACCESS_REQUEST_HISTORY where ID = 'event-1'"));
        }
    }

    private void applyChangelog(String databaseUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG_LOCATION,
                    new ClassLoaderResourceAccessor(),
                    database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private Set<String> columnsOf(Connection connection, String tableName) throws SQLException {
        Set<String> columnNames = new HashSet<>();
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, null)) {
            while (columns.next()) {
                columnNames.add(columns.getString("COLUMN_NAME"));
            }
        }
        return columnNames;
    }

    private Set<String> changelogIds(Connection connection) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (ResultSet result = connection.createStatement().executeQuery("select ID from DATABASECHANGELOG")) {
            while (result.next()) {
                ids.add(result.getString(1));
            }
        }
        return ids;
    }

    private Set<String> indexNamesOf(Connection connection, String tableName) throws SQLException {
        Set<String> indexNames = new HashSet<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                String indexName = indexes.getString("INDEX_NAME");
                if (indexName != null) {
                    indexNames.add(indexName);
                }
            }
        }
        return indexNames;
    }

    private java.util.List<String> indexColumnsOf(Connection connection, String tableName, String indexName)
            throws SQLException {
        java.util.Map<Short, String> columns = new java.util.TreeMap<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                if (indexName.equals(indexes.getString("INDEX_NAME"))) {
                    columns.put(indexes.getShort("ORDINAL_POSITION"), indexes.getString("COLUMN_NAME"));
                }
            }
        }
        return java.util.List.copyOf(columns.values());
    }

    private void insertPendingRequest(Connection connection, String requestId) throws SQLException {
        insertRequest(connection, requestId, null);
    }

    private void insertJitEntitlement(Connection connection, String entitlementId, String realmId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into AR_ENTITLEMENT (
                    ID, REALM_ID, RESOURCE_TYPE, RESOURCE_ID, DISPLAY_NAME, DESCRIPTION,
                    RISK_LEVEL, APPROVER_ROLE_ID, REQUESTABLE, DEFAULT_DURATION_SECONDS,
                    MAX_DURATION_SECONDS, ALLOW_PERMANENT, CREATED_TIMESTAMP, UPDATED_TIMESTAMP, VERSION)
                values (?, ?, 'REALM_ROLE', ?, 'access package', 'Temporary access',
                    'LOW', 'approver', FALSE, 2592000, 7776000, FALSE, 1, 1, 0)
                """)) {
            statement.setString(1, entitlementId);
            statement.setString(2, realmId);
            statement.setString(3, "role-" + entitlementId);
            statement.executeUpdate();
        }
    }

    private void insertAccessPackage(Connection connection, String entitlementId, String realmId,
            String groupId, String groupName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into AR_ACCESS_PACKAGE (ENTITLEMENT_ID, REALM_ID, GROUP_ID, GROUP_NAME)
                values (?, ?, ?, ?)
                """)) {
            statement.setString(1, entitlementId);
            statement.setString(2, realmId);
            statement.setString(3, groupId);
            statement.setString(4, groupName);
            statement.executeUpdate();
        }
    }

    private void insertRequest(Connection connection, String requestId, String decisionComment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into AR_ACCESS_REQUEST (
                    ID,
                    REALM_ID,
                    REQUESTER_ID,
                    ENTITLEMENT_ID,
                    RESOURCE_TYPE,
                    RESOURCE_ID,
                    RESOURCE_NAME_SNAPSHOT,
                    JUSTIFICATION,
                    DECISION_STATUS,
                    PROVISIONING_STATUS,
                    DECISION_COMMENT,
                    CREATED_TIMESTAMP,
                    UPDATED_TIMESTAMP,
                    VERSION)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, requestId);
            statement.setString(2, "realm-1");
            statement.setString(3, "requester-1");
            statement.setString(4, "entitlement-1");
            statement.setString(5, "REALM_ROLE");
            statement.setString(6, "role-1");
            statement.setString(7, "Finance Reader");
            statement.setString(8, "Access is needed for auditing.");
            statement.setString(9, "PENDING");
            statement.setString(10, "NOT_STARTED");
            statement.setString(11, decisionComment);
            statement.setLong(12, 1_700_000_000_000L);
            statement.setLong(13, 1_700_000_000_000L);
            statement.setLong(14, 0);
            statement.executeUpdate();
        }
    }

    private void insertAuditEvent(Connection connection, String eventId, String requestId, String comment)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into AR_ACCESS_REQUEST_HISTORY (
                    ID,
                    REQUEST_ID,
                    REALM_ID,
                    EVENT_TYPE,
                    ACTOR_ID,
                    EVENT_TIMESTAMP,
                    COMMENT)
                values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, eventId);
            statement.setString(2, requestId);
            statement.setString(3, "realm-1");
            statement.setString(4, "REQUEST_APPROVED");
            statement.setString(5, "approver-1");
            statement.setLong(6, 1_700_000_000_000L);
            statement.setString(7, comment);
            statement.executeUpdate();
        }
    }

    private String valueOf(Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            result.next();
            return result.getString(1);
        }
    }

    private String databaseUrl() {
        return "jdbc:h2:mem:access_requests_changelog_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }
}
