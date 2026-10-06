package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalAssurancePolicy;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApiDto.AdminCapabilitiesResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApprovalDto.CapabilitiesResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApprovalDto.DecisionSubmission;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.CatalogResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.EntitlementCreation;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.EntitlementUpdate;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.AccessPackageCreation;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.AccessPackageResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.AccessPackageRoleUpdate;
import ch.anass.keycloak.accessrequests.spi.realm.dto.NotificationDto.NotificationDeliverySummaryResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ProvisioningDto.ProvisioningClosureSubmission;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RevocationDto.RevocationResolutionSubmission;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RevocationDto.RevocationSubmission;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.RequestSubmission;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;

public final class AccessRequestRealmResource {

    private final AccessRequestCatalogHandler catalogHandler;
    private final AccessRequestRequesterHandler requesterHandler;
    private final AccessRequestApprovalHandler approvalHandler;
    private final AccessRequestAdminHandler adminHandler;

    public AccessRequestRealmResource(KeycloakSession session) {
        AccessRequestServiceFactory services = new AccessRequestServiceFactory(session);
        this.catalogHandler = new AccessRequestCatalogHandler(services);
        this.requesterHandler = new AccessRequestRequesterHandler(services);
        this.approvalHandler = new AccessRequestApprovalHandler(services);
        this.adminHandler = new AccessRequestAdminHandler(services);
    }

    @GET
    @Path("catalog")
    @Produces(MediaType.APPLICATION_JSON)
    public CatalogResponse catalog(
            @QueryParam("type") ResourceType resourceType,
            @QueryParam("search") String search,
            @QueryParam("riskLevel") RiskLevel riskLevel,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return catalogHandler.catalog(resourceType, search, riskLevel, page, size);
    }

    @OPTIONS
    @Path("catalog")
    public Response catalogOptions() {
        return catalogHandler.catalogOptions();
    }

    @GET
    @Path("admin/entitlements")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCatalogEntitlements(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return catalogHandler.listCatalogEntitlements(page, size);
    }

    @POST
    @Path("admin/entitlements")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createEntitlement(EntitlementCreation submission) {
        return catalogHandler.createEntitlement(submission);
    }

    @POST
    @Path("admin/access-packages")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createAccessPackage(AccessPackageCreation submission) {
        return catalogHandler.createAccessPackage(submission);
    }

    @GET
    @Path("admin/access-packages/{packageId}")
    @Produces(MediaType.APPLICATION_JSON)
    public AccessPackageResponse getAccessPackage(@PathParam("packageId") String packageId) {
        return catalogHandler.getAccessPackage(packageId);
    }

    @PUT
    @Path("admin/access-packages/{packageId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateAccessPackageRoles(@PathParam("packageId") String packageId,
            AccessPackageRoleUpdate submission) {
        return catalogHandler.updateAccessPackageRoles(packageId, submission);
    }

    @GET
    @Path("admin/entitlements/{entitlementId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEntitlement(@PathParam("entitlementId") String entitlementId) {
        return catalogHandler.getEntitlement(entitlementId);
    }

    @PUT
    @Path("admin/entitlements/{entitlementId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateEntitlement(
            @PathParam("entitlementId") String entitlementId,
            EntitlementUpdate submission) {
        return catalogHandler.updateEntitlement(entitlementId, submission);
    }

    @DELETE
    @Path("admin/entitlements/{entitlementId}")
    public Response deactivateEntitlement(@PathParam("entitlementId") String entitlementId) {
        return catalogHandler.deactivateEntitlement(entitlementId);
    }

    @POST
    @Path("requests")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submitRequest(RequestSubmission submission) {
        return requesterHandler.submitRequest(submission);
    }

    @GET
    @Path("mine")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listRequests(
            @QueryParam("status") String decisionStatus,
            @QueryParam("resourceType") String resourceType,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return requesterHandler.listRequests(decisionStatus, resourceType, from, to, page, size);
    }

    @GET
    @Path("admin/capabilities")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminCapabilitiesResponse adminCapabilities() {
        return adminHandler.adminCapabilities();
    }

    @GET
    @Path("admin/assurance-policy")
    @Produces(MediaType.APPLICATION_JSON)
    public Response approvalAssurancePolicy() {
        return adminHandler.approvalAssurancePolicy();
    }

    @PUT
    @Path("admin/assurance-policy")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateApprovalAssurancePolicy(ApprovalAssurancePolicy policy) {
        return adminHandler.updateApprovalAssurancePolicy(policy);
    }

    @GET
    @Path("admin/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listAuditEvents(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("type") String type,
            @QueryParam("requesterId") String requesterId,
            @QueryParam("actorId") String actorId,
            @QueryParam("requestId") String requestId,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return adminHandler.listAuditEvents(from, to, type, requesterId, actorId, requestId, page, size);
    }

    @GET
    @Path("admin/audit-users")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchAuditUsers(@QueryParam("search") String search) {
        return adminHandler.searchAuditUsers(search);
    }

    @GET
    @Path("admin/requests/{requestId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response administrativeRequestDetails(@PathParam("requestId") String requestId,
            @DefaultValue("0") @QueryParam("historyPage") int historyPage,
            @DefaultValue("20") @QueryParam("historySize") int historySize) {
        return adminHandler.administrativeRequestDetails(requestId, historyPage, historySize);
    }

    @GET
    @Path("admin/notification-deliveries")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listFailedNotificationDeliveries(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return adminHandler.listFailedNotificationDeliveries(page, size);
    }

    @GET
    @Path("admin/provisioning-failures")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listFailedProvisioningRequests(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size,
            @DefaultValue("OPEN") @QueryParam("state") String state) {
        return adminHandler.listFailedProvisioningRequests(page, size, state);
    }

    @GET
    @Path("admin/revocation-failures")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listRevocationFailures(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size,
            @DefaultValue("OPEN") @QueryParam("state") String state) {
        return adminHandler.listRevocationFailures(page, size, state);
    }

    @POST
    @Path("admin/grants/{requestId}/revocation/retry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response retryGrantRevocation(@PathParam("requestId") String requestId) {
        return adminHandler.retryGrantRevocation(requestId);
    }

    @POST
    @Path("admin/grants/{requestId}/revocation")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response revokeGrant(@PathParam("requestId") String requestId, RevocationSubmission submission) {
        return adminHandler.revokeGrant(requestId, submission);
    }

    @POST
    @Path("admin/grants/{requestId}/revocation/resolve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resolveGrantRevocation(@PathParam("requestId") String requestId,
            RevocationResolutionSubmission submission) {
        return adminHandler.resolveGrantRevocation(requestId, submission);
    }

    @GET
    @Path("admin/notification-deliveries/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public NotificationDeliverySummaryResponse notificationDeliverySummary() {
        return adminHandler.notificationDeliverySummary();
    }

    @POST
    @Path("admin/notification-deliveries/{deliveryId}/retry")
    public Response retryFailedNotificationDelivery(@PathParam("deliveryId") String deliveryId) {
        return adminHandler.retryFailedNotificationDelivery(deliveryId);
    }

    @POST
    @Path("admin/requests/{requestId}/provisioning/retry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response retryFailedProvisioning(@PathParam("requestId") String requestId) {
        return adminHandler.retryFailedProvisioning(requestId);
    }

    @POST
    @Path("admin/requests/{requestId}/provisioning/close")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response closeFailedProvisioning(
            @PathParam("requestId") String requestId, ProvisioningClosureSubmission submission) {
        return adminHandler.closeFailedProvisioning(requestId, submission);
    }

    @GET
    @Path("admin/references")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listKeycloakReferences(
            @QueryParam("type") ResourceType resourceType,
            @QueryParam("search") String search,
            @QueryParam("selectedId") String selectedId,
            @DefaultValue("0") @QueryParam("first") int first,
            @DefaultValue("50") @QueryParam("max") int max) {
        return catalogHandler.listKeycloakReferences(resourceType, search, selectedId, first, max);
    }

    @GET
    @Path("mine/{requestId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response requestDetails(@PathParam("requestId") String requestId) {
        return requesterHandler.requestDetails(requestId);
    }

    @GET
    @Path("pending")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listPendingRequests(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return approvalHandler.listPendingRequests(page, size);
    }

    @GET
    @Path("capabilities")
    @Produces(MediaType.APPLICATION_JSON)
    public CapabilitiesResponse capabilities() {
        return approvalHandler.capabilities();
    }

    @POST
    @Path("{requestId}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelRequest(@PathParam("requestId") String requestId) {
        return requesterHandler.cancelRequest(requestId);
    }

    @POST
    @Path("{requestId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response approveRequest(
            @PathParam("requestId") String requestId,
            DecisionSubmission submission) {
        return approvalHandler.approveRequest(requestId, submission);
    }

    @POST
    @Path("{requestId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rejectRequest(
            @PathParam("requestId") String requestId,
            DecisionSubmission submission) {
        return approvalHandler.rejectRequest(requestId, submission);
    }

}
