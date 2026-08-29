package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.port.DuplicatePendingRequestException;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;

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
                               entity.approverId = :approverId,
                               entity.decisionComment = :decisionComment,
                               entity.version = entity.version + 1
                         where entity.id = :id
                           and entity.realmId = :realmId
                           and entity.version = :expectedVersion
                        """)
                .setParameter("decisionStatus", request.decisionStatus())
                .setParameter("approverId", request.approverId())
                .setParameter("decisionComment", request.decisionComment())
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
