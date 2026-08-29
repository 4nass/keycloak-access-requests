package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogEntry;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogResult;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;

import java.util.Objects;
import java.util.Set;

public final class CatalogService {

    private final EntitlementRepository entitlementRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final EffectiveAccessChecker effectiveAccessChecker;

    public CatalogService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            EffectiveAccessChecker effectiveAccessChecker) {
        this.entitlementRepository = Objects.requireNonNull(entitlementRepository);
        this.accessRequestRepository = Objects.requireNonNull(accessRequestRepository);
        this.effectiveAccessChecker = Objects.requireNonNull(effectiveAccessChecker);
    }

    public CatalogResult findRequestable(CatalogQuery query, String requesterId) {
        CatalogQuery validatedQuery = Objects.requireNonNull(query, "query must not be null");
        String validatedRequesterId = requireText(requesterId, "requesterId");
        CatalogPage page = entitlementRepository.findRequestable(validatedQuery);
        Set<String> entitlementIds = page.items().stream()
                .map(entitlement -> entitlement.id())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> pendingEntitlementIds = accessRequestRepository.findPendingEntitlementIds(
                validatedQuery.realmId(), validatedRequesterId, entitlementIds);

        return new CatalogResult(
                page.items().stream()
                        .map(entitlement -> new CatalogEntry(
                                entitlement,
                                effectiveAccessChecker.hasAccess(
                                        validatedQuery.realmId(), validatedRequesterId, entitlement),
                                pendingEntitlementIds.contains(entitlement.id())))
                        .toList(),
                page.page(),
                page.size(),
                page.total());
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
