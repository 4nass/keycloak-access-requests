package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueEntry;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.DuplicatePendingRequestException;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
                        """, Long.class)
                .setParameter("realmId", realmId)
                .setParameter("decisionStatus", DecisionStatus.APPROVED)
                .setParameter("provisioningStatus", ProvisioningStatus.FAILED)
                .getSingleResult();
        List<FailedProvisioningRequest> items = entityManager.createQuery("""
                        select request.id,
                               request.requesterId,
                               request.entitlementId,
                               request.resourceType,
                               request.resourceNameSnapshot,
                               request.decisionStatus,
                               request.provisioningStatus,
                               request.updatedTimestamp
                          from AccessRequestEntity request
                         where request.realmId = :realmId
                           and request.decisionStatus = :decisionStatus
                           and request.provisioningStatus = :provisioningStatus
                         order by request.updatedTimestamp desc, request.id asc
                        """, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("decisionStatus", DecisionStatus.APPROVED)
                .setParameter("provisioningStatus", ProvisioningStatus.FAILED)
                .setFirstResult(offset)
                .setMaxResults(size)
                .getResultList()
                .stream()
                .map(row -> new FailedProvisioningRequest(
                        (String) row[0],
                        (String) row[1],
                        (String) row[2],
                        (ResourceType) row[3],
                        (String) row[4],
                        (DecisionStatus) row[5],
                        (ProvisioningStatus) row[6],
                        Instant.ofEpochMilli((Long) row[7])))
                .toList();
        return new FailedProvisioningPage(items, page, size, total);
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
            Instant updatedAt) {
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
