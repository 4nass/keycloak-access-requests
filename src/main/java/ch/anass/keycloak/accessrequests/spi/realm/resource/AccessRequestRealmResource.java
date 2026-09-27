package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApiDto.AdminCapabilitiesResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApprovalDto.CapabilitiesResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApprovalDto.DecisionSubmission;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.CatalogResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.EntitlementCreation;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.EntitlementUpdate;
import ch.anass.keycloak.accessrequests.spi.realm.dto.NotificationDto.NotificationDeliverySummaryResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ProvisioningDto.ProvisioningClosureSubmission;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.RequestSubmission;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
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

    private final AccessRequestCatalogResource catalogResource;
    private final AccessRequestRequesterResource requesterResource;
    private final AccessRequestApprovalResource approvalResource;
    private final AccessRequestAdminResource adminResource;

    public AccessRequestRealmResource(KeycloakSession session) {
        AccessRequestServiceFactory services = new AccessRequestServiceFactory(session);
        this.catalogResource = new AccessRequestCatalogResource(services);
        this.requesterResource = new AccessRequestRequesterResource(services);
        this.approvalResource = new AccessRequestApprovalResource(services);
        this.adminResource = new AccessRequestAdminResource(services);
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
        return catalogResource.catalog(resourceType, search, riskLevel, page, size);
    }

    @OPTIONS
    @Path("catalog")
    public Response catalogOptions() {
        return catalogResource.catalogOptions();
    }

    @GET
    @Path("admin/entitlements")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCatalogEntitlements(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return catalogResource.listCatalogEntitlements(page, size);
    }

    @POST
    @Path("admin/entitlements")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createEntitlement(EntitlementCreation submission) {
        return catalogResource.createEntitlement(submission);
    }

    @GET
    @Path("admin/entitlements/{entitlementId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEntitlement(@PathParam("entitlementId") String entitlementId) {
        return catalogResource.getEntitlement(entitlementId);
    }

    @PUT
    @Path("admin/entitlements/{entitlementId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateEntitlement(
            @PathParam("entitlementId") String entitlementId,
            EntitlementUpdate submission) {
        return catalogResource.updateEntitlement(entitlementId, submission);
    }

    @POST
    @Path("requests")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submitRequest(RequestSubmission submission) {
        return requesterResource.submitRequest(submission);
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
        return requesterResource.listRequests(decisionStatus, resourceType, from, to, page, size);
    }

    @GET
    @Path("admin/capabilities")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminCapabilitiesResponse adminCapabilities() {
        return adminResource.adminCapabilities();
    }

    @GET
    @Path("admin/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listAuditEvents(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("type") String type,
            @QueryParam("actorId") String actorId,
            @QueryParam("requestId") String requestId,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return adminResource.listAuditEvents(from, to, type, actorId, requestId, page, size);
    }

    @GET
    @Path("admin/requests/{requestId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response administrativeRequestDetails(@PathParam("requestId") String requestId,
            @DefaultValue("0") @QueryParam("historyPage") int historyPage,
            @DefaultValue("20") @QueryParam("historySize") int historySize) {
        return adminResource.administrativeRequestDetails(requestId, historyPage, historySize);
    }

    @GET
    @Path("admin/notification-deliveries")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listFailedNotificationDeliveries(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return adminResource.listFailedNotificationDeliveries(page, size);
    }

    @GET
    @Path("admin/provisioning-failures")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listFailedProvisioningRequests(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size,
            @DefaultValue("OPEN") @QueryParam("state") String state) {
        return adminResource.listFailedProvisioningRequests(page, size, state);
    }

    @GET
    @Path("admin/notification-deliveries/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public NotificationDeliverySummaryResponse notificationDeliverySummary() {
        return adminResource.notificationDeliverySummary();
    }

    @POST
    @Path("admin/notification-deliveries/{deliveryId}/retry")
    public Response retryFailedNotificationDelivery(@PathParam("deliveryId") String deliveryId) {
        return adminResource.retryFailedNotificationDelivery(deliveryId);
    }

    @POST
    @Path("admin/requests/{requestId}/provisioning/retry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response retryFailedProvisioning(@PathParam("requestId") String requestId) {
        return adminResource.retryFailedProvisioning(requestId);
    }

    @POST
    @Path("admin/requests/{requestId}/provisioning/close")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response closeFailedProvisioning(
            @PathParam("requestId") String requestId, ProvisioningClosureSubmission submission) {
        return adminResource.closeFailedProvisioning(requestId, submission);
    }

    @GET
    @Path("admin/references")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listKeycloakReferences(
            @QueryParam("type") ResourceType resourceType,
            @QueryParam("search") String search,
            @DefaultValue("50") @QueryParam("max") int max) {
        return catalogResource.listKeycloakReferences(resourceType, search, max);
    }

    @GET
    @Path("mine/{requestId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response requestDetails(@PathParam("requestId") String requestId) {
        return requesterResource.requestDetails(requestId);
    }

    @GET
    @Path("pending")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listPendingRequests(
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        return approvalResource.listPendingRequests(page, size);
    }

    @GET
    @Path("capabilities")
    @Produces(MediaType.APPLICATION_JSON)
    public CapabilitiesResponse capabilities() {
        return approvalResource.capabilities();
    }

    @POST
    @Path("{requestId}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelRequest(@PathParam("requestId") String requestId) {
        return requesterResource.cancelRequest(requestId);
    }

    @POST
    @Path("{requestId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response approveRequest(
            @PathParam("requestId") String requestId,
            DecisionSubmission submission) {
        return approvalResource.approveRequest(requestId, submission);
    }

    @POST
    @Path("{requestId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rejectRequest(
            @PathParam("requestId") String requestId,
            DecisionSubmission submission) {
        return approvalResource.rejectRequest(requestId, submission);
    }

}
