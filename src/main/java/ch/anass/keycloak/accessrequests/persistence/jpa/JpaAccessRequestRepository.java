package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEventType;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueEntry;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.DuplicatePendingRequestException;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JpaAccessRequestRepository implements AccessRequestRepository {

    private static final int FAILED_PROVISIONING_MAX_PAGE_SIZE = 100;
    private static final int FAILED_PROVISIONING_MAX_OFFSET = 10_000;

    private final EntityManager entityManager;

    public JpaAccessRequestRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<AccessRequest> findById(String realmId, String requestId) {
        AccessRequestEntity entity = entityManager.find(AccessRequestEntity.class, requestId);
        if (entity == null || !entity.realmId().equals(realmId)) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    public Optional<AccessRequest> findByIdForUpdate(String realmId, String requestId) {
        AccessRequestEntity entity = entityManager.find(
                AccessRequestEntity.class, requestId, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null || !entity.realmId().equals(realmId)) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    public AccessRequestPage findByRequester(AccessRequestQuery query) {
        RequesterQueryDefinition definition = requesterQueryDefinition(query);
        long total = bind(entityManager.createQuery(
                        "select count(entity) " + definition.fromAndWhere(), Long.class), definition, query)
                .getSingleResult();
        var items = bind(entityManager.createQuery(
                        "select entity " + definition.fromAndWhere()
                                + " order by entity.createdTimestamp desc, entity.id asc",
                        AccessRequestEntity.class), definition, query)
                .setFirstResult(query.offset())
                .setMaxResults(query.size())
                .getResultList()
                .stream()
                .map(AccessRequestEntity::toDomain)
                .toList();
        return new AccessRequestPage(items, query.page(), query.size(), total);
    }

    @Override
    public ApprovalQueuePage findPendingForApprover(ApprovalQueueQuery query) {
        if (query.approverRoleIds().isEmpty()) {
            return new ApprovalQueuePage(List.of(), query.page(), query.size(), 0);
        }
        long total = bindApprovalQueueQuery(entityManager.createQuery(
                "select count(request) " + approvalQueueFromAndWhere(), Long.class), query)
                .getSingleResult();
        var items = bindApprovalQueueQuery(entityManager.createQuery(
                        "select request, entitlement.riskLevel " + approvalQueueFromAndWhere()
                                + " order by request.createdTimestamp desc, request.id asc",
                        Object[].class), query)
                .setFirstResult(query.offset())
                .setMaxResults(query.size())
                .getResultList()
                .stream()
                .map(row -> new ApprovalQueueEntry(
                        ((AccessRequestEntity) row[0]).toDomain(),
                        (RiskLevel) row[1]))
                .toList();
        return new ApprovalQueuePage(items, query.page(), query.size(), total);
    }

    /**
     * Lists approved requests whose entitlement provisioning failed, scoped to one realm.
     */
    public FailedProvisioningPage findFailedProvisioning(String realmId, int page, int size) {
        return findProvisioningFailures(realmId, page, size, false);
    }

    /**
     * Lists closed provisioning failures for the same realm, without making them retryable.
     */
    public FailedProvisioningPage findClosedProvisioning(String realmId, int page, int size) {
        return findProvisioningFailures(realmId, page, size, true);
    }

    private FailedProvisioningPage findProvisioningFailures(String realmId, int page, int size, boolean closed) {
        if (realmId == null || realmId.isBlank()) {
            throw new IllegalArgumentException("realmId must not be blank");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > FAILED_PROVISIONING_MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between 1 and " + FAILED_PROVISIONING_MAX_PAGE_SIZE);
        }
        final int offset;
        try {
            offset = Math.multiplyExact(page, size);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("page and size are too large", exception);
        }
        if (offset > FAILED_PROVISIONING_MAX_OFFSET) {
            throw new IllegalArgumentException(
                    "page and size exceed the maximum offset of " + FAILED_PROVISIONING_MAX_OFFSET);
        }

        long total = entityManager.createQuery("""
                        select count(request)
                          from AccessRequestEntity request
                         where request.realmId = :realmId
                           and request.decisionStatus = :decisionStatus
                           and request.provisioningStatus = :provisioningStatus
                           and ((:closed = true and request.provisioningClosedTimestamp is not null)
                                or (:closed = false and request.provisioningClosedTimestamp is null))
                        """, Long.class)
                .setParameter("realmId", realmId)
                .setParameter("decisionStatus", DecisionStatus.APPROVED)
                .setParameter("provisioningStatus", ProvisioningStatus.FAILED)
                .setParameter("closed", closed)
                .getSingleResult();
        List<Object[]> rows = entityManager.createQuery("""
                        select request.id,
                               request.requesterId,
                               request.entitlementId,
                               request.resourceType,
                               request.resourceNameSnapshot,
                               request.decisionStatus,
                               request.provisioningStatus,
                               request.updatedTimestamp,
                               request.provisioningClosedTimestamp,
                               request.provisioningClosedBy,
                               request.provisioningClosureReason
                          from AccessRequestEntity request
                         where request.realmId = :realmId
                           and request.decisionStatus = :decisionStatus
                           and request.provisioningStatus = :provisioningStatus
                           and ((:closed = true and request.provisioningClosedTimestamp is not null)
                                or (:closed = false and request.provisioningClosedTimestamp is null))
                         order by request.provisioningClosedTimestamp desc, request.updatedTimestamp desc, request.id asc
                        """, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("decisionStatus", DecisionStatus.APPROVED)
                .setParameter("provisioningStatus", ProvisioningStatus.FAILED)
                .setParameter("closed", closed)
                .setFirstResult(offset)
                .setMaxResults(size)
                .getResultList();
        Map<String, ProvisioningFailureCode> failureCodes = latestFailureCodes(
                realmId, rows.stream().map(row -> (String) row[0]).toList());
        List<FailedProvisioningRequest> items = rows.stream()
                .map(row -> new FailedProvisioningRequest(
                        (String) row[0],
                        (String) row[1],
                        (String) row[2],
                        (ResourceType) row[3],
                        (String) row[4],
                        (DecisionStatus) row[5],
                        (ProvisioningStatus) row[6],
                        Instant.ofEpochMilli((Long) row[7]),
                        failureCodes.getOrDefault((String) row[0], ProvisioningFailureCode.UNKNOWN),
                        row[8] == null ? null : Instant.ofEpochMilli((Long) row[8]),
                        (String) row[9],
                        (String) row[10]))
                .toList();
        return new FailedProvisioningPage(items, page, size, total);
    }

    private Map<String, ProvisioningFailureCode> latestFailureCodes(String realmId, List<String> requestIds) {
        Map<String, ProvisioningFailureCode> codes = new HashMap<>();
        if (requestIds.isEmpty()) {
            return codes;
        }
        entityManager.createQuery("""
                        select event.requestId, event.metadata
                          from AccessRequestEventEntity event
                         where event.realmId = :realmId
                           and event.requestId in :requestIds
                           and event.type = :eventType
                           and not exists (
                               select newer.id
                                 from AccessRequestEventEntity newer
                                where newer.realmId = event.realmId
                                  and newer.requestId = event.requestId
                                  and newer.type = event.type
                                   and (newer.requestVersion is not null and event.requestVersion is null
                                        or (newer.requestVersion is not null and event.requestVersion is not null
                                            and newer.requestVersion > event.requestVersion)
                                        or ((newer.requestVersion is null and event.requestVersion is null
                                                or newer.requestVersion = event.requestVersion)
                                            and newer.occurredAt > event.occurredAt))
                           )
                        """, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("requestIds", requestIds)
                .setParameter("eventType", AccessRequestEventType.PROVISIONING_FAILED)
                .getResultList()
                .forEach(row -> codes.merge(
                        (String) row[0], ProvisioningFailureCode.fromStoredValue((String) row[1]),
                        (previous, ambiguous) -> ProvisioningFailureCode.UNKNOWN));
        return codes;
    }

    public record FailedProvisioningPage(List<FailedProvisioningRequest> items, int page, int size, long total) {
        public FailedProvisioningPage {
            items = List.copyOf(items);
        }
    }

    public record FailedProvisioningRequest(
            String id,
            String requesterId,
            String entitlementId,
            ResourceType resourceType,
            String resourceName,
            DecisionStatus decisionStatus,
            ProvisioningStatus provisioningStatus,
            Instant updatedAt,
            ProvisioningFailureCode failureCode,
            Instant closedAt,
            String closedBy,
            String closureReason) {
    }

    private static String approvalQueueFromAndWhere() {
        return """
                from AccessRequestEntity request, EntitlementEntity entitlement
                 where request.realmId = :realmId
                   and request.decisionStatus = :decisionStatus
                   and request.requesterId <> :approverId
                   and entitlement.id = request.entitlementId
                   and entitlement.realmId = request.realmId
                   and entitlement.approverRoleId in :approverRoleIds
                """;
    }

    private static <T> jakarta.persistence.TypedQuery<T> bindApprovalQueueQuery(
            jakarta.persistence.TypedQuery<T> typedQuery,
            ApprovalQueueQuery query) {
        return typedQuery
                .setParameter("realmId", query.realmId())
                .setParameter("decisionStatus", DecisionStatus.PENDING)
                .setParameter("approverId", query.approverId())
                .setParameter("approverRoleIds", query.approverRoleIds());
    }

    private static <T> jakarta.persistence.TypedQuery<T> bind(
            jakarta.persistence.TypedQuery<T> typedQuery,
            RequesterQueryDefinition definition,
            AccessRequestQuery query) {
        typedQuery.setParameter("realmId", query.realmId());
        typedQuery.setParameter("requesterId", query.requesterId());
        if (definition.hasDecisionStatus()) {
            typedQuery.setParameter("decisionStatus", query.decisionStatus());
        }
        if (definition.hasResourceType()) {
            typedQuery.setParameter("resourceType", query.resourceType());
        }
        if (definition.hasFrom()) {
            typedQuery.setParameter("from", query.from().toEpochMilli());
        }
        if (definition.hasTo()) {
            typedQuery.setParameter("to", query.to().toEpochMilli());
        }
        return typedQuery;
    }

    private static RequesterQueryDefinition requesterQueryDefinition(AccessRequestQuery query) {
        List<String> predicates = new ArrayList<>(List.of(
                "entity.realmId = :realmId",
                "entity.requesterId = :requesterId"));
        boolean hasDecisionStatus = query.decisionStatus() != null;
        if (hasDecisionStatus) {
            predicates.add("entity.decisionStatus = :decisionStatus");
        }
        boolean hasResourceType = query.resourceType() != null;
        if (hasResourceType) {
            predicates.add("entity.resourceType = :resourceType");
        }
        boolean hasFrom = query.from() != null;
        if (hasFrom) {
            predicates.add("entity.createdTimestamp >= :from");
        }
        boolean hasTo = query.to() != null;
        if (hasTo) {
            predicates.add("entity.createdTimestamp <= :to");
        }
        return new RequesterQueryDefinition(
                "from AccessRequestEntity entity where " + String.join(" and ", predicates),
                hasDecisionStatus,
                hasResourceType,
                hasFrom,
                hasTo);
    }

    private record RequesterQueryDefinition(
            String fromAndWhere,
            boolean hasDecisionStatus,
            boolean hasResourceType,
            boolean hasFrom,
            boolean hasTo) {
    }

    @Override
    public Set<String> findPendingEntitlementIds(String realmId, String requesterId, Set<String> entitlementIds) {
        if (entitlementIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(entityManager.createQuery("""
                        select entity.entitlementId
                          from AccessRequestEntity entity
                         where entity.realmId = :realmId
                           and entity.requesterId = :requesterId
                           and entity.decisionStatus = :decisionStatus
                           and entity.entitlementId in :entitlementIds
                        """, String.class)
                .setParameter("realmId", realmId)
                .setParameter("requesterId", requesterId)
                .setParameter("decisionStatus", DecisionStatus.PENDING)
                .setParameter("entitlementIds", entitlementIds)
                .getResultList());
    }

    @Override
    public Optional<AccessRequest> createIfNoPending(AccessRequest request) {
        try {
            entityManager.persist(AccessRequestEntity.from(request));
            entityManager.flush();
            return Optional.of(request);
        } catch (PersistenceException exception) {
            if (!isPendingConstraintViolation(exception)) {
                throw exception;
            }
            throw new DuplicatePendingRequestException();
        }
    }

    @Override
    public Optional<AccessRequest> updateIfVersionMatches(AccessRequest request, long expectedVersion) {
        int updated = entityManager.createQuery("""
                        update AccessRequestEntity entity
                           set entity.decisionStatus = :decisionStatus,
                                entity.provisioningStatus = :provisioningStatus,
                                entity.approverId = :approverId,
                                entity.decisionComment = :decisionComment,
                                entity.updatedTimestamp = :updatedTimestamp,
                                entity.decidedTimestamp = :decidedTimestamp,
                                entity.provisioningClosedTimestamp = :provisioningClosedTimestamp,
                                entity.provisioningClosedBy = :provisioningClosedBy,
                                entity.provisioningClosureReason = :provisioningClosureReason,
                                entity.version = entity.version + 1
                         where entity.id = :id
                           and entity.realmId = :realmId
                           and entity.version = :expectedVersion
                        """)
                .setParameter("decisionStatus", request.decisionStatus())
                .setParameter("provisioningStatus", request.provisioningStatus())
                .setParameter("approverId", request.approverId())
                .setParameter("decisionComment", request.decisionComment())
                .setParameter("updatedTimestamp", request.updatedAt().toEpochMilli())
                .setParameter(
                        "decidedTimestamp",
                        request.decidedAt() == null ? null : request.decidedAt().toEpochMilli())
                .setParameter("provisioningClosedTimestamp", request.provisioningClosedAt() == null
                        ? null : request.provisioningClosedAt().toEpochMilli())
                .setParameter("provisioningClosedBy", request.provisioningClosedBy())
                .setParameter("provisioningClosureReason", request.provisioningClosureReason())
                .setParameter("id", request.id())
                .setParameter("realmId", request.realmId())
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        if (updated == 0) {
            return Optional.empty();
        }
        entityManager.flush();
        entityManager.clear();
        return findById(request.realmId(), request.id());
    }

    private static boolean isPendingConstraintViolation(PersistenceException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && ("UK_ACCESS_REQUEST_PENDING".equals(violation.getConstraintName())
                    || "23505".equals(violation.getSQLException().getSQLState()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
