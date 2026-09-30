package ch.anass.keycloak.accessrequests.spi.realm.dto;

import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogEntry;
import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogResult;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementPage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;

import java.util.List;

/** JSON payloads for the entitlement catalog and its administration. */
public final class CatalogDto {

    private CatalogDto() {
    }

    public record CatalogResponse(List<CatalogItemResponse> items, int page, int size, long total) {
        public static CatalogResponse from(CatalogResult page) {
            return new CatalogResponse(page.items().stream().map(CatalogItemResponse::from).toList(),
                    page.page(), page.size(), page.total());
        }
    }

    public record CatalogItemResponse(
            String id, ResourceType type, String name, String description, RiskLevel riskLevel,
            boolean alreadyGranted, boolean pendingRequest,
            long defaultDurationSeconds, long maxDurationSeconds, boolean allowPermanent) {
        public static CatalogItemResponse from(CatalogEntry entry) {
            return new CatalogItemResponse(entry.entitlement().id(), entry.entitlement().resourceType(),
                    entry.entitlement().displayName(), entry.entitlement().description(),
                    entry.entitlement().riskLevel(), entry.alreadyGranted(), entry.pendingRequest(),
                    entry.entitlement().durationPolicy().defaultDuration().toSeconds(),
                    entry.entitlement().durationPolicy().maxDuration().toSeconds(),
                    entry.entitlement().durationPolicy().allowPermanent());
        }
    }

    public record EntitlementCreation(
            ResourceType resourceType, String resourceId, String displayName, String description,
            RiskLevel riskLevel, String approverRoleId, Long defaultDurationSeconds,
            Long maxDurationSeconds, Boolean allowPermanent) {
    }

    public record AccessPackageCreation(
            String displayName, String description, RiskLevel riskLevel, String approverRoleId,
            Long defaultDurationSeconds, Long maxDurationSeconds, Boolean allowPermanent,
            List<AccessPackageRole> roleMappings) {
    }

    public record AccessPackageRole(ResourceType type, String roleId) {
    }

    public record AccessPackageResponse(
            String entitlementId, String groupId, String groupName, boolean groupExists, boolean configurationValid,
            List<AccessPackageRoleResponse> roleMappings) {
    }

    public record AccessPackageRoleResponse(ResourceType type, String roleId, String name, boolean missing) {
    }

    public record EntitlementUpdate(
            String displayName, String description, RiskLevel riskLevel, String approverRoleId,
            Boolean requestable, Long version, Long defaultDurationSeconds,
            Long maxDurationSeconds, Boolean allowPermanent) {
    }

    public record EntitlementResponse(
            String id, ResourceType resourceType, String resourceId, String displayName,
            String description, RiskLevel riskLevel, String approverRoleId, boolean requestable,
            String createdAt, String updatedAt, long version,
            long defaultDurationSeconds, long maxDurationSeconds, boolean allowPermanent) {
        public static EntitlementResponse from(Entitlement entitlement) {
            return new EntitlementResponse(entitlement.id(), entitlement.resourceType(),
                    entitlement.resourceId(), entitlement.displayName(), entitlement.description(),
                    entitlement.riskLevel(), entitlement.approverRoleId(), entitlement.requestable(),
                    entitlement.createdAt().toString(), entitlement.updatedAt().toString(),
                    entitlement.version(), entitlement.durationPolicy().defaultDuration().toSeconds(),
                    entitlement.durationPolicy().maxDuration().toSeconds(),
                    entitlement.durationPolicy().allowPermanent());
        }
    }

    public record EntitlementListResponse(List<EntitlementResponse> items, int page, int size, long total) {
        public static EntitlementListResponse from(EntitlementPage page) {
            return new EntitlementListResponse(page.items().stream().map(EntitlementResponse::from).toList(),
                    page.page(), page.size(), page.total());
        }
    }

    public record KeycloakReferenceListResponse(List<KeycloakReferenceResponse> items, int nextFirst,
            boolean hasMore) {
    }

    public record KeycloakReferenceResponse(ResourceType type, String id, String name, String description) {
    }
}
