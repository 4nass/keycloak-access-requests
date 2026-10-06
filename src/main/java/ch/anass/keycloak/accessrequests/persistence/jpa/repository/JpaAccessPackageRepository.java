package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.port.AccessPackageRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.AccessPackageEntity;
import jakarta.persistence.EntityManager;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

public final class JpaAccessPackageRepository implements AccessPackageRepository {

    private final EntityManager entityManager;

    public JpaAccessPackageRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    public void create(AccessPackage accessPackage) {
        Objects.requireNonNull(accessPackage, "accessPackage must not be null");
        EntitlementEntity entitlement = entityManager.find(EntitlementEntity.class, accessPackage.entitlementId());
        if (entitlement == null || !accessPackage.realmId().equals(entitlement.toDomain().realmId())) {
            throw new IllegalArgumentException("The package entitlement does not exist in the specified realm");
        }
        entityManager.persist(AccessPackageEntity.from(accessPackage));
        entityManager.flush();
    }

    @Override
    public Optional<AccessPackage> findByEntitlementId(String realmId, String entitlementId) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(entitlementId, "entitlementId must not be null");
        AccessPackageEntity entity = entityManager.find(AccessPackageEntity.class, entitlementId);
        return entity != null && realmId.equals(entity.realmId())
                ? Optional.of(entity.toDomain()) : Optional.empty();
    }

    public void replaceRoleMappings(String realmId, String entitlementId,
            List<AccessPackage.RoleMapping> mappings) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(entitlementId, "entitlementId must not be null");
        AccessPackageEntity entity = entityManager.find(AccessPackageEntity.class, entitlementId);
        if (entity == null || !realmId.equals(entity.realmId())) {
            throw new IllegalArgumentException("The access package does not exist in this realm");
        }
        entity.replaceRoleMappings(mappings);
        entityManager.flush();
    }
}
