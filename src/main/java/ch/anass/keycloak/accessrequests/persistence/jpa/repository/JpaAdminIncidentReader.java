package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestNotificationOutboxEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessRequestNotificationOutboxState;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.GrantRevocationFailureEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** A read-only, realm-scoped worklist. Each source keeps its own lifecycle and action API. */
public final class JpaAdminIncidentReader {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CURSOR_LENGTH = 2_048;

    private final EntityManager entityManager;
    private final JpaAccessRequestRepository requests;

    public JpaAdminIncidentReader(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.requests = new JpaAccessRequestRepository(entityManager);
    }

    public IncidentPage findPage(String realmId, IncidentType type, IncidentState state,
            Instant from, Instant to, String cursor, int size) {
        if (realmId == null || realmId.isBlank() || type == null || state == null
                || size < 1 || size > MAX_PAGE_SIZE || (from != null && to != null && from.isAfter(to))) {
            throw new IllegalArgumentException("Invalid incident query");
        }
        CursorPosition position = decodeCursor(cursor, realmId, type, state, from, to);
        Long fromMillis;
        Long toMillis;
        try {
            fromMillis = from == null ? null : ceilToMillis(from);
            toMillis = to == null ? null : to.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid incident date range", exception);
        }
        List<String> sources = new ArrayList<>();
        if ((type == IncidentType.ALL || type == IncidentType.NOTIFICATION) && state != IncidentState.CLOSED) {
            sources.add("""
                    select 'NOTIFICATION' as incident_kind, n.ID as incident_id,
                           coalesce(n.LAST_ATTEMPT_TIMESTAMP, n.NEXT_ATTEMPT_TIMESTAMP) as occurred_at
                      from AR_NOTIFICATION_OUTBOX n
                     where n.REALM_ID = :realmId and n.STATE = 'FAILED'
                    """);
        }
        if (type == IncidentType.ALL || type == IncidentType.PROVISIONING) {
            sources.add("""
                    select 'PROVISIONING' as incident_kind, r.ID as incident_id,
                           coalesce(r.PROVISIONING_CLOSED_TIMESTAMP, r.UPDATED_TIMESTAMP) as occurred_at
                      from AR_ACCESS_REQUEST r
                     where r.REALM_ID = :realmId and r.DECISION_STATUS = 'APPROVED'
                       and r.PROVISIONING_STATUS = 'FAILED'
                    """ + statePredicate("r.PROVISIONING_CLOSED_TIMESTAMP", state));
        }
        if (type == IncidentType.ALL || type == IncidentType.REVOCATION) {
            sources.add("""
                    select 'REVOCATION' as incident_kind, f.REQUEST_ID as incident_id,
                           coalesce(f.RESOLVED_TIMESTAMP, f.LAST_FAILED_TIMESTAMP) as occurred_at
                      from AR_GRANT_REVOCATION_FAILURE f
                      join AR_ACCESS_GRANT g on g.REQUEST_ID = f.REQUEST_ID and g.REALM_ID = f.REALM_ID
                     where f.REALM_ID = :realmId
                    """ + statePredicate("f.RESOLVED_TIMESTAMP", state));
        }
        if (sources.isEmpty()) {
            return new IncidentPage(List.of(), size, null);
        }
        String union = String.join(" union all ", sources);
        List<String> predicates = new ArrayList<>();
        if (fromMillis != null) predicates.add("occurred_at >= :fromMillis");
        if (toMillis != null) predicates.add("occurred_at <= :toMillis");
        if (position != null) predicates.add("(occurred_at < :cursorAt or (occurred_at = :cursorAt"
                + " and (incident_kind > :cursorKind or (incident_kind = :cursorKind"
                + " and incident_id > :cursorId))))");
        String where = predicates.isEmpty() ? "" : " where " + String.join(" and ", predicates);
        Query query = entityManager.createNativeQuery("select incident_kind, incident_id, occurred_at from ("
                        + union + ") incidents" + where
                        + " order by occurred_at desc, incident_kind asc, incident_id asc")
                .setParameter("realmId", realmId)
                .setMaxResults(size + 1);
        if (fromMillis != null) query.setParameter("fromMillis", fromMillis);
        if (toMillis != null) query.setParameter("toMillis", toMillis);
        if (position != null) {
            query.setParameter("cursorAt", position.occurredAt());
            query.setParameter("cursorKind", position.kind());
            query.setParameter("cursorId", position.id());
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        boolean hasNext = rows.size() > size;
        List<Object[]> selected = hasNext ? rows.subList(0, size) : rows;
        List<IncidentItem> items = selected.stream()
                .map(row -> hydrate(realmId, IncidentType.valueOf((String) row[0]),
                        (String) row[1], Instant.ofEpochMilli(((Number) row[2]).longValue())))
                .filter(Objects::nonNull)
                .toList();
        String nextCursor = hasNext ? encodeCursor(realmId, type, state, from, to, selected.getLast()) : null;
        return new IncidentPage(items, size, nextCursor);
    }

    private static long ceilToMillis(Instant instant) {
        long millis = instant.toEpochMilli();
        return instant.getNano() % 1_000_000 == 0 ? millis : Math.addExact(millis, 1);
    }

    private static String encodeCursor(String realmId, IncidentType type, IncidentState state,
            Instant from, Instant to, Object[] row) {
        String value = String.join("\n", "1", realmId, type.name(), state.name(),
                from == null ? "" : from.toString(), to == null ? "" : to.toString(),
                Long.toString(((Number) row[2]).longValue()), (String) row[0], (String) row[1]);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static CursorPosition decodeCursor(String cursor, String realmId, IncidentType type,
            IncidentState state, Instant from, Instant to) {
        if (cursor == null) return null;
        if (cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("Invalid incident cursor");
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\n", -1);
            if (parts.length != 9 || !"1".equals(parts[0]) || !realmId.equals(parts[1])
                    || !type.name().equals(parts[2]) || !state.name().equals(parts[3])
                    || !(from == null ? "" : from.toString()).equals(parts[4])
                    || !(to == null ? "" : to.toString()).equals(parts[5])
                    || parts[7].isBlank() || parts[8].isBlank()
                    || IncidentType.valueOf(parts[7]) == IncidentType.ALL) {
                throw new IllegalArgumentException("Invalid incident cursor");
            }
            return new CursorPosition(Long.parseLong(parts[6]), parts[7], parts[8]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid incident cursor", exception);
        }
    }

    private record CursorPosition(long occurredAt, String kind, String id) {
    }

    private static String statePredicate(String column, IncidentState state) {
        return switch (state) {
            case OPEN -> " and " + column + " is null";
            case CLOSED -> " and " + column + " is not null";
            case ALL -> "";
        };
    }

    private IncidentItem hydrate(String realmId, IncidentType type, String id, Instant occurredAt) {
        return switch (type) {
            case NOTIFICATION -> {
                AccessRequestNotificationOutboxEntity notification = entityManager.find(
                        AccessRequestNotificationOutboxEntity.class, id);
                yield notification != null && realmId.equals(notification.realmId())
                        && notification.state() == AccessRequestNotificationOutboxState.FAILED
                        ? new IncidentItem(type, id, occurredAt, notification, null, null) : null;
            }
            case PROVISIONING -> requests.findProvisioningFailureById(realmId, id)
                    .map(request -> new IncidentItem(type, id, occurredAt, null, request, null))
                    .orElse(null);
            case REVOCATION -> {
                GrantRevocationFailureEntity failure = entityManager.find(GrantRevocationFailureEntity.class, id);
                AccessGrantEntity grant = entityManager.find(AccessGrantEntity.class, id);
                yield failure != null && grant != null && realmId.equals(failure.realmId())
                        && realmId.equals(grant.realmId())
                        ? new IncidentItem(type, id, occurredAt, null, null,
                                new JpaGrantRevocationFailureRepository.FailureItem(
                                        grant.toDomain(), failure.toDomain())) : null;
            }
            case ALL -> throw new IllegalStateException("ALL is a query filter, not an incident type");
        };
    }

    public enum IncidentType { ALL, NOTIFICATION, PROVISIONING, REVOCATION }

    public enum IncidentState { OPEN, CLOSED, ALL }

    public record IncidentItem(IncidentType type, String id, Instant occurredAt,
            AccessRequestNotificationOutboxEntity notification,
            JpaAccessRequestRepository.FailedProvisioningRequest provisioning,
            JpaGrantRevocationFailureRepository.FailureItem revocation) {
    }

    public record IncidentPage(List<IncidentItem> items, int size, String nextCursor) {
    }
}
