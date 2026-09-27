package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.catalog.CatalogResult;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementAuditEvent;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementPage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.EntitlementQuery;
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
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.KeycloakReferenceResponse;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.RealmModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RoleModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

final class AccessRequestCatalogResource extends AccessRequestEndpointSupport {

    AccessRequestCatalogResource(AccessRequestServiceFactory services) {
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
            return Response.ok(EntitlementListResponse.from(entitlementPage)).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_ENTITLEMENT_QUERY", exception.getMessage(), null);
        }
    }

    public Response createEntitlement(EntitlementCreation submission) {
        AccessRequestManager manager = requireAccessRequestManager();
        EntitlementCreation validatedSubmission = requireEntitlementCreation(submission);
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
            return Response.status(Response.Status.CREATED).entity(EntitlementResponse.from(persisted)).build();
        } catch (DuplicateEntitlementException exception) {
            return error(
                    Response.Status.CONFLICT,
                    "ENTITLEMENT_ALREADY_EXISTS",
                    exception.getMessage(),
                    null);
        }
    }

    public Response getEntitlement(String entitlementId) {
        AccessRequestManager manager = requireAccessRequestManager();
        return Response.ok(EntitlementResponse.from(findEntitlement(manager.realm(), entitlementId))).build();
    }

    public Response updateEntitlement(
            String entitlementId,
            EntitlementUpdate submission) {
        AccessRequestManager manager = requireAccessRequestManager();
        EntitlementUpdate validatedSubmission = requireEntitlementUpdate(submission);
        validateApproverRole(manager.realm(), validatedSubmission.approverRoleId());
        Entitlement current = findEntitlement(manager.realm(), entitlementId);
        try {
            Instant updatedAt = Instant.now();
            Entitlement updated = current.updateDetails(
                    validatedSubmission.displayName(),
                    validatedSubmission.description(),
                    validatedSubmission.riskLevel(),
                    validatedSubmission.approverRoleId(),
                    updatedAt);
            updated = validatedSubmission.requestable()
                    ? updated.publish(updatedAt)
                    : updated.unpublish(updatedAt);
            return Response.ok(EntitlementResponse.from(
                    persistEntitlementUpdate(updated, validatedSubmission.version(), manager))).build();
        } catch (ConcurrentEntitlementModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_ENTITLEMENT_MODIFICATION", exception.getMessage(), null);
        }
    }

    public Response listKeycloakReferences(
            ResourceType resourceType,
            String search,
            int max) {
        AccessRequestManager manager = requireAccessRequestManager();
        if (resourceType == null) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REFERENCE_TYPE", "type must be provided", null);
        }
        if (max < 1 || max > 100) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REFERENCE_QUERY", "max must be between 1 and 100", null);
        }

        List<KeycloakReferenceResponse> references = references(manager.realm(), resourceType)
                .filter(reference -> matches(reference, search))
                .sorted(Comparator.comparing(KeycloakReferenceResponse::name, String.CASE_INSENSITIVE_ORDER))
                .limit(max)
                .toList();
        return Response.ok(new KeycloakReferenceListResponse(references)).build();
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
        return submission;
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

    private static Stream<KeycloakReferenceResponse> references(RealmModel realm, ResourceType resourceType) {
        return switch (resourceType) {
            case REALM_ROLE -> realm.getRolesStream()
                    .filter(role -> !role.isClientRole())
                    .map(role -> roleReference(resourceType, role, role.getName()));
            case CLIENT_ROLE -> realm.getClientsStream()
                    .flatMap(client -> client.getRolesStream()
                            .map(role -> roleReference(resourceType, role, client.getClientId() + " / " + role.getName())));
            case GROUP -> realm.getGroupsStream()
                    .map(group -> new KeycloakReferenceResponse(
                            resourceType, group.getId(), groupPath(group), ""));
        };
    }

    private static KeycloakReferenceResponse roleReference(
            ResourceType resourceType, RoleModel role, String name) {
        return new KeycloakReferenceResponse(
                resourceType, role.getId(), name, Objects.requireNonNullElse(role.getDescription(), ""));
    }

    private static boolean matches(KeycloakReferenceResponse reference, String search) {
        if (isBlank(search)) {
            return true;
        }
        String normalizedSearch = search.trim().toLowerCase(Locale.ROOT);
        return reference.id().toLowerCase(Locale.ROOT).contains(normalizedSearch)
                || reference.name().toLowerCase(Locale.ROOT).contains(normalizedSearch)
                || reference.description().toLowerCase(Locale.ROOT).contains(normalizedSearch);
    }

    private static String groupPath(GroupModel group) {
        List<String> path = new ArrayList<>();
        GroupModel current = group;
        while (current != null) {
            path.addFirst(current.getName());
            current = current.getParent();
        }
        return "/" + String.join("/", path);
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
