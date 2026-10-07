package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailure;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantRevocationFailureCode;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRevocationFailureRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessGrantEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.GrantRevocationFailureEntity;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JpaGrantRevocationFailureRepository implements AccessGrantRevocationFailureRepository {

    private final EntityManager entityManager;
    private final JpaAccessGrantRepository grants;

    public JpaGrantRevocationFailureRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.grants = new JpaAccessGrantRepository(entityManager);
    }

    @Override
    public Optional<GrantRevocationFailure> record(String realmId, String requestId,
            GrantRevocationFailureCode code, Instant now) {
        Objects.requireNonNull(code);
        Objects.requireNonNull(now);
        AccessGrant grant = grants.findByRequestIdForUpdate(realmId, requestId).orElse(null);
        if (grant == null || !grant.canManuallyRevoke()) {
            return Optional.empty();
        }
        GrantRevocationFailureEntity failure = entityManager.find(GrantRevocationFailureEntity.class, requestId);
        if (failure == null) {
            failure = new GrantRevocationFailureEntity(realmId, requestId, code, now);
            entityManager.persist(failure);
        } else {
            if (!realmId.equals(failure.realmId())) {
                throw new IllegalStateException("Revocation failure belongs to another realm");
            }
            failure.record(code, now);
        }
        return Optional.of(failure.toDomain());
    }

    @Override
    public void resolve(String realmId, String requestId, Instant now) {
        GrantRevocationFailureEntity failure = entityManager.find(GrantRevocationFailureEntity.class, requestId);
        if (failure != null && realmId.equals(failure.realmId()) && failure.toDomain().isOpen()) {
            failure.resolve(now);
        }
    }

    @Override
    public Optional<GrantRevocationFailure> findOpen(String realmId, String requestId) {
        GrantRevocationFailureEntity failure = entityManager.find(GrantRevocationFailureEntity.class, requestId);
        return failure != null && realmId.equals(failure.realmId()) && failure.toDomain().isOpen()
                ? Optional.of(failure.toDomain()) : Optional.empty();
    }

    public FailurePage findPage(String realmId, boolean open, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid revocation failure page");
        }
        String where = " where failure.realmId = :realmId and failure.resolvedTimestamp is "
                + (open ? "null" : "not null");
        long total = entityManager.createQuery("select count(failure) from GrantRevocationFailureEntity failure"
                        + where, Long.class)
                .setParameter("realmId", realmId)
                .getSingleResult();
        List<FailureItem> items = entityManager.createQuery("""
                        select failure, grant from GrantRevocationFailureEntity failure, AccessGrantEntity grant
                        """ + where + " and grant.requestId = failure.requestId"
                        + " order by failure.lastFailedTimestamp desc, failure.requestId", Object[].class)
                .setParameter("realmId", realmId)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList().stream()
                .map(row -> new FailureItem(((AccessGrantEntity) row[1]).toDomain(),
                        ((GrantRevocationFailureEntity) row[0]).toDomain()))
                .toList();
        return new FailurePage(items, page, size, total);
    }

    public record FailureItem(AccessGrant grant, GrantRevocationFailure failure) {
    }

    public record FailurePage(List<FailureItem> items, int page, int size, long total) {
    }
}
