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

class AccessRequestJpaChangelogTest {

    private static final String CHANGELOG_LOCATION = "META-INF/access-requests-changelog.xml";

    @Test
    void createsTheAccessRequestAndHistoryTablesIdempotently() throws Exception {
        String databaseUrl = databaseUrl();

        applyChangelog(databaseUrl);
        applyChangelog(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
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
                            "DECISION_STATUS",
                            "PROVISIONING_STATUS",
                            "APPROVER_ID",
                            "DECISION_COMMENT",
                            "CREATED_TIMESTAMP",
                            "UPDATED_TIMESTAMP",
                            "DECIDED_TIMESTAMP",
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
                            "COMMENT",
                            "METADATA"),
                    columnsOf(connection, "AR_ACCESS_REQUEST_HISTORY"));
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

    private void insertPendingRequest(Connection connection, String requestId) throws SQLException {
        insertRequest(connection, requestId, null);
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
