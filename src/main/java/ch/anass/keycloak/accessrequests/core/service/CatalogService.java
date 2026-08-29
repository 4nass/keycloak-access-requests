package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;

import java.util.Objects;

public final class CatalogService {

    private final EntitlementRepository entitlementRepository;

    public CatalogService(EntitlementRepository entitlementRepository) {
        this.entitlementRepository = Objects.requireNonNull(entitlementRepository);
    }

    public CatalogPage findRequestable(CatalogQuery query) {
        return entitlementRepository.findRequestable(Objects.requireNonNull(query));
    }
}
