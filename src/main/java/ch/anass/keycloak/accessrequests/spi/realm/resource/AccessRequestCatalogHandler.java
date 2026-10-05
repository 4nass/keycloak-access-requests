package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogResult;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.DurationPolicy;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementAuditEvent;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementPage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementQuery;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.DuplicateEntitlementException;
import ch.anass.keycloak.accessrequests.core.service.ConcurrentEntitlementModificationException;
import ch.anass.keycloak.accessrequests.spi.realm.KeycloakEntitlementAdminEventPublisher;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.CatalogResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.EntitlementCreation;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.EntitlementListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.EntitlementResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.EntitlementUpdate;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.KeycloakReferenceListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.AccessPackageCreation;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.AccessPackageResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.AccessPackageRoleResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.RealmModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RoleModel;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestErrors.error;

final class AccessRequestCatalogHandler extends AccessRequestHandlerSupport {

    private static EntitlementResponse present(Entitlement entitlement, AdminDisplayNameResolver names) {
        return EntitlementResponse.from(entitlement,
                names.resource(entitlement.resourceType(), entitlement.resourceId()),
                names.role(entitlement.approverRoleId()));
    }

    AccessRequestCatalogHandler(AccessRequestServiceFactory services) {
        super(services);
    }

    public CatalogResponse catalog(
            ResourceType resourceType,
            String search,
            RiskLevel riskLevel,
            int page,
            int size) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        CatalogResult catalogResult;
        try {
            catalogResult = catalogService(authenticatedRequest).findRequestable(
                    new CatalogQuery(
                            authenticatedRequest.realm().getId(), resourceType, search, riskLevel, page, size),
                    authenticatedRequest.user().getId());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage(), exception);
        }
        return CatalogResponse.from(catalogResult);
    }

    public Response catalogOptions() {
        return Response.noContent()
                .header("Allow", "GET, OPTIONS")
                .build();
    }

    public Response listCatalogEntitlements(
            int page,
            int size) {
        AccessRequestManager manager = requireAccessRequestManager();
        try {
            EntitlementPage entitlementPage = entitlementRepository().findAll(
                    new EntitlementQuery(manager.realm().getId(), page, size));
            AdminDisplayNameResolver names = adminNames(manager.realm());
            return Response.ok(EntitlementListResponse.from(entitlementPage,
                    entitlement -> present(entitlement, names))).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_ENTITLEMENT_QUERY", exception.getMessage(), null);
        }
    }

    public Response createEntitlement(EntitlementCreation submission) {
        AccessRequestManager manager = requireAccessRequestManager();
        EntitlementCreation validatedSubmission = requireEntitlementCreation(submission);
        if (Boolean.TRUE.equals(validatedSubmission.requestable())) {
            return error(Response.Status.CONFLICT, "ACCESS_PACKAGE_REQUIRED",
                    "Only a bound access package can be made requestable", null);
        }
        validateKeycloakReferences(manager.realm(), validatedSubmission);
        Entitlement created = Entitlement.create(
                UUID.randomUUID().toString(),
                manager.realm().getId(),
                validatedSubmission.resourceType(),
                validatedSubmission.resourceId(),
                validatedSubmission.displayName(),
                validatedSubmission.description(),
                validatedSubmission.riskLevel(),
                validatedSubmission.approverRoleId(),
                creationDurationPolicy(validatedSubmission),
                Instant.now());
        try {
            Entitlement persisted = transaction().execute(() -> {
                Entitlement createdEntitlement = entitlementRepository().create(created);
                entitlementAuditEventPublisher().publish(
                        EntitlementAuditEvent.created(createdEntitlement, manager.user().getId()));
                new KeycloakEntitlementAdminEventPublisher(session, manager.realm(), manager.auth())
                        .created(createdEntitlement);
                return createdEntitlement;
            });
            return Response.status(Response.Status.CREATED)
                    .entity(present(persisted, adminNames(manager.realm()))).build();
        } catch (DuplicateEntitlementException exception) {
            return error(
                    Response.Status.CONFLICT,
                    "ENTITLEMENT_ALREADY_EXISTS",
                    exception.getMessage(),
                    null);
        }
    }

    public Response createAccessPackage(AccessPackageCreation submission) {
        AccessRequestManager manager = requireAccessRequestManager();
        AccessPackageCreation validated = requireAccessPackageCreation(submission);
        DurationPolicy durationPolicy = creationDurationPolicy(validated.riskLevel(),
                validated.defaultDurationSeconds(), validated.maxDurationSeconds(), validated.allowPermanent());
        List<AccessPackage.RoleMapping> mappings = validated.roleMappings().stream()
                .map(role -> new AccessPackage.RoleMapping(role.type(), role.roleId()))
                .toList();
        String entitlementId = UUID.randomUUID().toString();

        try {
            Entitlement persisted = transaction().execute(() -> {
                validateApproverRole(manager.realm(), validated.approverRoleId());
                AccessPackage accessPackage = accessPackageGroupFactory(manager.realm())
                        .create(entitlementId, manager.realm().getId(), mappings);
                Instant createdAt = Instant.now();
                Entitlement created = Entitlement.create(entitlementId, manager.realm().getId(),
                        ResourceType.GROUP, accessPackage.groupId(), validated.displayName(),
                        validated.description(), validated.riskLevel(), validated.approverRoleId(),
                        durationPolicy, createdAt)
                        .withAutoApproval(Boolean.TRUE.equals(validated.autoApprove()), createdAt);
                Entitlement saved = entitlementRepository().create(created);
                accessPackageRepository().create(accessPackage);
                entitlementAuditEventPublisher().publish(
                        EntitlementAuditEvent.created(saved, manager.user().getId()));
                new KeycloakEntitlementAdminEventPublisher(session, manager.realm(), manager.auth()).created(saved);
                return saved;
            });
            return Response.status(Response.Status.CREATED)
                    .entity(present(persisted, adminNames(manager.realm()))).build();
        } catch (DuplicateEntitlementException exception) {
            return error(Response.Status.CONFLICT, "ENTITLEMENT_ALREADY_EXISTS", exception.getMessage(), null);
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_ACCESS_PACKAGE", exception.getMessage(), null);
        }
    }

    public AccessPackageResponse getAccessPackage(String packageId) {
        AccessRequestManager manager = requireAccessRequestManager();
        AccessPackage accessPackage = accessPackageRepository()
                .findByEntitlementId(manager.realm().getId(), packageId)
                .orElseThrow(() -> new NotFoundException("Access package not found: " + packageId));
        Entitlement entitlement = findEntitlement(manager.realm(), packageId);
        if (entitlement.resourceType() != ResourceType.GROUP
                || !entitlement.resourceId().equals(accessPackage.groupId())) {
            throw new IllegalStateException("The package binding does not match its entitlement");
        }
        GroupModel group = session.groups().getGroupById(manager.realm(), accessPackage.groupId());
        List<AccessPackageRoleResponse> roles = accessPackage.roleMappings().stream().map(mapping -> {
            RoleModel role = manager.realm().getRoleById(mapping.roleId());
            boolean missing = role == null || role.isClientRole() != (mapping.type() == ResourceType.CLIENT_ROLE);
            return new AccessPackageRoleResponse(mapping.type(), mapping.roleId(),
                    missing ? null : KeycloakReferenceSearch.roleDisplayName(mapping.type(), role), missing);
        }).toList();
        return new AccessPackageResponse(packageId, accessPackage.groupId(), accessPackage.groupName(),
                group != null && accessPackage.groupName().equals(group.getName()),
                isPackageConfigured(group, accessPackage), roles);
    }

    public Response getEntitlement(String entitlementId) {
        AccessRequestManager manager = requireAccessRequestManager();
        return Response.ok(present(findEntitlement(manager.realm(), entitlementId),
                adminNames(manager.realm()))).build();
    }

    public Response updateEntitlement(
            String entitlementId,
            EntitlementUpdate submission) {
        AccessRequestManager manager = requireAccessRequestManager();
        EntitlementUpdate validatedSubmission = requireEntitlementUpdate(submission);
        validateApproverRole(manager.realm(), validatedSubmission.approverRoleId());
        Entitlement current = findEntitlement(manager.realm(), entitlementId);
        if (validatedSubmission.requestable()) {
            var binding = accessPackageRepository().findByEntitlementId(manager.realm().getId(), entitlementId);
            if (binding.isEmpty() || current.resourceType() != ResourceType.GROUP
                    || !current.resourceId().equals(binding.get().groupId())) {
                return error(Response.Status.CONFLICT, "ACCESS_PACKAGE_REQUIRED",
                        "Only a bound access package can be made requestable", null);
            }
            if (!isPackageConfigured(
                    session.groups().getGroupById(manager.realm(), binding.get().groupId()), binding.get())) {
                return error(Response.Status.CONFLICT, "INVALID_ACCESS_PACKAGE_CONFIGURATION",
                        "The access package group or its role mappings have changed", null);
            }
        }
        try {
            Instant updatedAt = Instant.now();
            DurationPolicy durationPolicy = updateDurationPolicy(current, validatedSubmission);
            Entitlement updated = current.updateDetails(
                    validatedSubmission.displayName(),
                    validatedSubmission.description(),
                    validatedSubmission.riskLevel(),
                    validatedSubmission.approverRoleId(),
                    durationPolicy,
                    updatedAt);
            if (validatedSubmission.autoApprove() != null) {
                updated = updated.withAutoApproval(validatedSubmission.autoApprove(), updatedAt);
            }
            updated = validatedSubmission.requestable()
                    ? updated.publish(updatedAt)
                    : updated.unpublish(updatedAt);
            return Response.ok(present(
                    persistEntitlementUpdate(updated, validatedSubmission.version(), manager),
                    adminNames(manager.realm()))).build();
        } catch (ConcurrentEntitlementModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_ENTITLEMENT_MODIFICATION", exception.getMessage(), null);
        }
    }

    public Response listKeycloakReferences(
            ResourceType resourceType,
            String search,
            String selectedId,
            int first,
            int max) {
        AccessRequestManager manager = requireAccessRequestManager();
        if (resourceType == null) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REFERENCE_TYPE", "type must be provided", null);
        }
        if (max < 1 || max > 100) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REFERENCE_QUERY", "max must be between 1 and 100", null);
        }
        if (first < 0 || first > Integer.MAX_VALUE - max - 1) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REFERENCE_QUERY", "first is outside the supported range", null);
        }
        if ((search != null && search.length() > 256) || (selectedId != null && selectedId.length() > 256)) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REFERENCE_QUERY", "search and selectedId must be at most 256 characters", null);
        }
        var page = KeycloakReferenceSearch.find(manager.realm(), session.roles(), session.groups(),
                resourceType, search, selectedId, first, max);
        return Response.ok(new KeycloakReferenceListResponse(page.items(), page.nextFirst(), page.hasMore())).build();
    }

    private static boolean isPackageConfigured(GroupModel group, AccessPackage accessPackage) {
        if (group == null || group.getParent() != null || !accessPackage.groupName().equals(group.getName())) {
            return false;
        }
        List<AccessPackage.RoleMapping> actual = group.getRoleMappingsStream()
                .map(role -> new AccessPackage.RoleMapping(
                        role.isClientRole() ? ResourceType.CLIENT_ROLE : ResourceType.REALM_ROLE, role.getId()))
                .toList();
        return actual.size() == accessPackage.roleMappings().size()
                && Set.copyOf(actual).equals(Set.copyOf(accessPackage.roleMappings()));
    }

    private static EntitlementCreation requireEntitlementCreation(EntitlementCreation submission) {
        if (submission == null
                || submission.resourceType() == null
                || isBlank(submission.resourceId())
                || isBlank(submission.displayName())
                || isBlank(submission.description())
                || submission.riskLevel() == null
                || isBlank(submission.approverRoleId())) {
            throw new BadRequestException(
                    "resourceType, resourceId, displayName, description, riskLevel, and approverRoleId must be provided");
        }
        return submission;
    }

    private static AccessPackageCreation requireAccessPackageCreation(AccessPackageCreation submission) {
        if (submission == null || isBlank(submission.displayName()) || isBlank(submission.description())
                || submission.riskLevel() == null || isBlank(submission.approverRoleId())
                || submission.roleMappings() == null || submission.roleMappings().isEmpty()
                || submission.roleMappings().size() > 100
                || submission.roleMappings().stream().anyMatch(role -> role == null || role.type() == null
                        || role.type() == ResourceType.GROUP || isBlank(role.roleId()))) {
            throw new BadRequestException("access package metadata and 1 to 100 realm or client roles must be provided");
        }
        requireLowRiskAutoApproval(submission.autoApprove(), submission.riskLevel());
        return submission;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static DurationPolicy creationDurationPolicy(EntitlementCreation submission) {
        return creationDurationPolicy(submission.riskLevel(), submission.defaultDurationSeconds(),
                submission.maxDurationSeconds(), submission.allowPermanent());
    }

    private static DurationPolicy creationDurationPolicy(RiskLevel riskLevel, Long defaultDurationSeconds,
            Long maxDurationSeconds, Boolean allowPermanent) {
        if (defaultDurationSeconds == null && maxDurationSeconds == null) {
            DurationPolicy defaults = DurationPolicy.defaultsFor(riskLevel);
            return new DurationPolicy(defaults.defaultDuration(), defaults.maxDuration(),
                    Boolean.TRUE.equals(allowPermanent));
        }
        return requireDurationPolicy(defaultDurationSeconds, maxDurationSeconds, allowPermanent);
    }

    private static DurationPolicy updateDurationPolicy(Entitlement current, EntitlementUpdate submission) {
        if (submission.defaultDurationSeconds() == null && submission.maxDurationSeconds() == null
                && submission.allowPermanent() == null) {
            return submission.riskLevel() == current.riskLevel()
                    ? current.durationPolicy() : DurationPolicy.defaultsFor(submission.riskLevel());
        }
        return requireDurationPolicy(submission.defaultDurationSeconds(), submission.maxDurationSeconds(),
                submission.allowPermanent());
    }

    private static DurationPolicy requireDurationPolicy(Long defaultSeconds, Long maxSeconds, Boolean allowPermanent) {
        if (defaultSeconds == null || maxSeconds == null) {
            throw new BadRequestException("defaultDurationSeconds and maxDurationSeconds must be provided together");
        }
        try {
            DurationPolicy policy = new DurationPolicy(Duration.ofSeconds(defaultSeconds), Duration.ofSeconds(maxSeconds),
                    Boolean.TRUE.equals(allowPermanent));
            DurationPolicy.expiryAt(Instant.now(), maxSeconds);
            return policy;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Duration bounds must be positive, ordered, and representable as an expiry",
                    exception);
        }
    }

    private static EntitlementUpdate requireEntitlementUpdate(EntitlementUpdate submission) {
        if (submission == null
                || isBlank(submission.displayName())
                || isBlank(submission.description())
                || submission.riskLevel() == null
                || isBlank(submission.approverRoleId())
                || submission.requestable() == null
                || submission.version() == null
                || submission.version() < 0) {
            throw new BadRequestException(
                    "displayName, description, riskLevel, approverRoleId, requestable, and a non-negative version must be provided");
        }
        requireLowRiskAutoApproval(submission.autoApprove(), submission.riskLevel());
        return submission;
    }

    private static void requireLowRiskAutoApproval(Boolean enabled, RiskLevel riskLevel) {
        if (Boolean.TRUE.equals(enabled) && riskLevel != RiskLevel.LOW) {
            throw new BadRequestException("autoApprove requires LOW risk");
        }
    }

    private void validateKeycloakReferences(RealmModel realm, EntitlementCreation submission) {
        switch (submission.resourceType()) {
            case REALM_ROLE -> requireRole(realm, submission.resourceId(), false, "resourceId");
            case CLIENT_ROLE -> requireRole(realm, submission.resourceId(), true, "resourceId");
            case GROUP -> requireGroup(realm, submission.resourceId());
        }
        validateApproverRole(realm, submission.approverRoleId());
    }

    private static void validateApproverRole(RealmModel realm, String approverRoleId) {
        requireRole(realm, approverRoleId, false, "approverRoleId");
    }

    private static RoleModel requireRole(RealmModel realm, String roleId, boolean clientRole, String fieldName) {
        RoleModel role = realm.getRoleById(roleId);
        if (role == null || role.isClientRole() != clientRole) {
            throw new BadRequestException(fieldName + " must reference an existing "
                    + (clientRole ? "client" : "realm") + " role");
        }
        return role;
    }

    private void requireGroup(RealmModel realm, String groupId) {
        GroupModel group = session.groups().getGroupById(realm, groupId);
        if (group == null) {
            throw new BadRequestException("resourceId must reference an existing group");
        }
    }

    private Entitlement findEntitlement(RealmModel realm, String entitlementId) {
        return entitlementRepository().findById(realm.getId(), entitlementId)
                .orElseThrow(() -> new NotFoundException("Entitlement not found: " + entitlementId));
    }

    private Entitlement persistEntitlementUpdate(Entitlement entitlement, long expectedVersion,
            AccessRequestManager manager) {
        return transaction().execute(() -> {
            Entitlement persisted = entitlementRepository()
                    .updateIfVersionMatches(entitlement, expectedVersion)
                    .orElseThrow(() -> new ConcurrentEntitlementModificationException(entitlement.id()));
            entitlementAuditEventPublisher().publish(
                    EntitlementAuditEvent.updated(persisted, manager.user().getId()));
            new KeycloakEntitlementAdminEventPublisher(session, manager.realm(), manager.auth())
                    .updated(persisted);
            return persisted;
        });
    }
}
