package ch.anass.keycloak.accessrequests.persistence.jpa.repository;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.JitAccessPackage;
import ch.anass.keycloak.accessrequests.core.port.JitAccessPackageRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.EntitlementEntity;
import ch.anass.keycloak.accessrequests.persistence.jpa.entity.JitAccessPackageEntity;
import jakarta.persistence.EntityManager;

import java.util.Objects;
import java.util.Optional;

public final class JpaJitAccessPackageRepository implements JitAccessPackageRepository {

    private final EntityManager entityManager;

    public JpaJitAccessPackageRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    public void create(JitAccessPackage accessPackage) {
        Objects.requireNonNull(accessPackage, "accessPackage must not be null");
        EntitlementEntity entitlement = entityManager.find(EntitlementEntity.class, accessPackage.entitlementId());
        if (entitlement == null || !accessPackage.realmId().equals(entitlement.toDomain().realmId())) {
            throw new IllegalArgumentException("The package entitlement does not exist in the specified realm");
        }
        entityManager.persist(JitAccessPackageEntity.from(accessPackage));
        entityManager.flush();
    }

    @Override
    public Optional<JitAccessPackage> findByEntitlementId(String realmId, String entitlementId) {
        Objects.requireNonNull(realmId, "realmId must not be null");
        Objects.requireNonNull(entitlementId, "entitlementId must not be null");
        JitAccessPackageEntity entity = entityManager.find(JitAccessPackageEntity.class, entitlementId);
        return entity != null && realmId.equals(entity.realmId())
                ? Optional.of(entity.toDomain()) : Optional.empty();
    }
}
