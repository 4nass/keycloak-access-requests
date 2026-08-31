package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.port.DuplicatePendingRequestException;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class JpaAccessRequestRepository implements AccessRequestRepository {

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
    public AccessRequestPage findPendingForApprover(ApprovalQueueQuery query) {
        if (query.approverRoleIds().isEmpty()) {
            return new AccessRequestPage(List.of(), query.page(), query.size(), 0);
        }
        long total = bindApprovalQueueQuery(entityManager.createQuery(
                "select count(request) " + approvalQueueFromAndWhere(), Long.class), query)
                .getSingleResult();
        var items = bindApprovalQueueQuery(entityManager.createQuery(
                        "select request " + approvalQueueFromAndWhere()
                                + " order by request.createdTimestamp desc, request.id asc",
                        AccessRequestEntity.class), query)
                .setFirstResult(query.offset())
                .setMaxResults(query.size())
                .getResultList()
                .stream()
                .map(AccessRequestEntity::toDomain)
                .toList();
        return new AccessRequestPage(items, query.page(), query.size(), total);
    }

    private static String approvalQueueFromAndWhere() {
        return """
                from AccessRequestEntity request
                 where request.realmId = :realmId
                   and request.decisionStatus = :decisionStatus
                   and request.requesterId <> :approverId
                   and exists (
                       select 1
                         from EntitlementEntity entitlement
                        where entitlement.id = request.entitlementId
                          and entitlement.realmId = request.realmId
                          and entitlement.approverRoleId in :approverRoleIds
                   )
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
