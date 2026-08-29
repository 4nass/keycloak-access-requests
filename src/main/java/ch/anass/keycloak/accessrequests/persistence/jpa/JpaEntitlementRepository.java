package ch.anass.keycloak.accessrequests.persistence.jpa;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

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
}
