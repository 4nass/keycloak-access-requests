package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementPage;
import ch.anass.keycloak.accessrequests.core.domain.EntitlementQuery;
import ch.anass.keycloak.accessrequests.core.port.DuplicateEntitlementException;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import org.hibernate.exception.ConstraintViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JpaEntitlementRepository implements EntitlementRepository {

    private final EntityManager entityManager;

    public JpaEntitlementRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Entitlement> findById(String realmId, String entitlementId) {
        EntitlementEntity entity = entityManager.find(EntitlementEntity.class, entitlementId);
        if (entity == null) {
            return Optional.empty();
        }
        Entitlement entitlement = entity.toDomain();
        return entitlement.realmId().equals(realmId) ? Optional.of(entitlement) : Optional.empty();
    }

    @Override
    public Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId) {
        return entityManager.createQuery("""
                        select entity
                          from EntitlementEntity entity
                         where entity.id = :entitlementId
                           and entity.realmId = :realmId
                        """, EntitlementEntity.class)
                .setParameter("entitlementId", entitlementId)
                .setParameter("realmId", realmId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .map(EntitlementEntity::toDomain);
    }

    /**
     * Returns both published and draft entitlements for realm administration.
     */
    public EntitlementPage findAll(EntitlementQuery query) {
        long total = entityManager.createQuery("""
                        select count(entity)
                          from EntitlementEntity entity
                         where entity.realmId = :realmId
                        """, Long.class)
                .setParameter("realmId", query.realmId())
                .getSingleResult();
        List<Entitlement> items = entityManager.createQuery("""
                        select entity
                          from EntitlementEntity entity
                         where entity.realmId = :realmId
                         order by entity.displayName asc, entity.id asc
                        """, EntitlementEntity.class)
                .setParameter("realmId", query.realmId())
                .setFirstResult(query.offset())
                .setMaxResults(query.size())
                .getResultList()
                .stream()
                .map(EntitlementEntity::toDomain)
                .toList();
        return new EntitlementPage(items, query.page(), query.size(), total);
    }

    /**
     * Persists a new draft entitlement and translates the resource uniqueness constraint into a domain error.
     */
    public Entitlement create(Entitlement entitlement) {
        try {
            entityManager.persist(EntitlementEntity.from(entitlement));
            entityManager.flush();
            return entitlement;
        } catch (PersistenceException exception) {
            if (isResourceConstraintViolation(exception)) {
                throw new DuplicateEntitlementException();
            }
            throw exception;
        }
    }

    /**
     * Applies a catalog change only when no concurrent administrator has already changed the entitlement.
     */
    public Optional<Entitlement> updateIfVersionMatches(Entitlement entitlement, long expectedVersion) {
        int updated = entityManager.createQuery("""
                        update EntitlementEntity entity
                           set entity.displayName = :displayName,
                               entity.description = :description,
                               entity.riskLevel = :riskLevel,
                               entity.approverRoleId = :approverRoleId,
                               entity.requestable = :requestable,
                               entity.updatedTimestamp = :updatedTimestamp,
                               entity.version = entity.version + 1
                         where entity.id = :id
                           and entity.realmId = :realmId
                           and entity.version = :expectedVersion
                        """)
                .setParameter("displayName", entitlement.displayName())
                .setParameter("description", entitlement.description())
                .setParameter("riskLevel", entitlement.riskLevel())
                .setParameter("approverRoleId", entitlement.approverRoleId())
                .setParameter("requestable", entitlement.requestable())
                .setParameter("updatedTimestamp", entitlement.updatedAt().toEpochMilli())
                .setParameter("id", entitlement.id())
                .setParameter("realmId", entitlement.realmId())
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        if (updated == 0) {
            return Optional.empty();
        }
        entityManager.flush();
        entityManager.clear();
        return findById(entitlement.realmId(), entitlement.id());
    }

    @Override
    public CatalogPage findRequestable(CatalogQuery query) {
        QueryDefinition definition = queryDefinition(query);
        long total = createCountQuery(definition, query).getSingleResult();
        List<Entitlement> items = createItemsQuery(definition, query)
                .setFirstResult(query.offset())
                .setMaxResults(query.size())
                .getResultList()
                .stream()
                .map(EntitlementEntity::toDomain)
                .toList();
        return new CatalogPage(items, query.page(), query.size(), total);
    }

    private TypedQuery<Long> createCountQuery(QueryDefinition definition, CatalogQuery query) {
        return bind(entityManager.createQuery(
                "select count(entity) " + definition.fromAndWhere(), Long.class), definition, query);
    }

    private TypedQuery<EntitlementEntity> createItemsQuery(QueryDefinition definition, CatalogQuery query) {
        return bind(entityManager.createQuery(
                "select entity " + definition.fromAndWhere() + " order by entity.displayName asc, entity.id asc",
                EntitlementEntity.class), definition, query);
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> query, QueryDefinition definition, CatalogQuery catalogQuery) {
        query.setParameter("realmId", catalogQuery.realmId());
        if (catalogQuery.resourceType() != null) {
            query.setParameter("resourceType", catalogQuery.resourceType());
        }
        if (catalogQuery.riskLevel() != null) {
            query.setParameter("riskLevel", catalogQuery.riskLevel());
        }
        if (definition.hasSearch()) {
            query.setParameter("search", "%" + catalogQuery.search().toLowerCase() + "%");
        }
        return query;
    }

    private static QueryDefinition queryDefinition(CatalogQuery query) {
        List<String> predicates = new ArrayList<>(List.of(
                "entity.realmId = :realmId",
                "entity.requestable = true"));
        if (query.resourceType() != null) {
            predicates.add("entity.resourceType = :resourceType");
        }
        if (query.riskLevel() != null) {
            predicates.add("entity.riskLevel = :riskLevel");
        }
        boolean hasSearch = query.search() != null;
        if (hasSearch) {
            predicates.add("(lower(entity.displayName) like :search or lower(entity.description) like :search)");
        }
        return new QueryDefinition("from EntitlementEntity entity where " + String.join(" and ", predicates), hasSearch);
    }

    private record QueryDefinition(String fromAndWhere, boolean hasSearch) {
    }

    private static boolean isResourceConstraintViolation(PersistenceException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && ("UK_ENTITLEMENT_RESOURCE".equals(violation.getConstraintName())
                    || "23505".equals(violation.getSQLException().getSQLState()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
