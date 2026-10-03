package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.request.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.approval.SelfApprovalException;
import ch.anass.keycloak.accessrequests.core.domain.approval.UnauthorizedApprovalException;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.AccessPackageRequiredException;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotRequestableException;
import ch.anass.keycloak.accessrequests.core.service.ConcurrentRequestModificationException;
import ch.anass.keycloak.accessrequests.core.service.RequestNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.RequestService;
import ch.anass.keycloak.accessrequests.core.service.InvalidRequestedDurationException;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApprovalDto.CapabilitiesResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApprovalDto.DecisionSubmission;
import ch.anass.keycloak.accessrequests.spi.realm.dto.ApprovalDto.PendingRequestListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.RequestResponse;
import jakarta.ws.rs.core.Response;

import static ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestErrors.error;

final class AccessRequestApprovalHandler extends AccessRequestHandlerSupport {

    AccessRequestApprovalHandler(AccessRequestServiceFactory services) {
        super(services);
    }

    public Response listPendingRequests(
            int page,
            int size) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            ApprovalQueuePage requestPage = approvalQueueService(authenticatedRequest).findPending(
                    authenticatedRequest.realm().getId(),
                    authenticatedRequest.user().getId(),
                    page,
                    size);
            return Response.ok(PendingRequestListResponse.from(requestPage,
                    new AccessRequestUserNameResolver(session, authenticatedRequest.realm())::resolve)).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST_QUERY", exception.getMessage(), null);
        }
    }

    public CapabilitiesResponse capabilities() {
        AuthenticatedRequest authenticatedRequest = authenticate();
        return new CapabilitiesResponse(approvalQueueService(authenticatedRequest).canApprove(
                authenticatedRequest.realm().getId(), authenticatedRequest.user().getId()));
    }

    public Response approveRequest(
            String requestId,
            DecisionSubmission submission) {
        return decide(requestId, submission, true);
    }

    public Response rejectRequest(
            String requestId,
            DecisionSubmission submission) {
        return decide(requestId, submission, false);
    }

    private Response decide(String requestId, DecisionSubmission submission, boolean approved) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        if (submission == null) {
            return error(
                    Response.Status.BAD_REQUEST,
                    "INVALID_DECISION_SUBMISSION",
                    "A decision payload must be provided",
                    requestId);
        }
        try {
            RequestService requestService = requestService(authenticatedRequest);
            AccessRequest decided = approved
                    ? requestService.approve(
                            authenticatedRequest.realm().getId(),
                            requestId,
                            authenticatedRequest.user().getId(),
                            submission.comment())
                    : requestService.reject(
                            authenticatedRequest.realm().getId(),
                            requestId,
                            authenticatedRequest.user().getId(),
                            submission.comment());
            return Response.ok(RequestResponse.from(decided)).build();
        } catch (RequestNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "REQUEST_NOT_FOUND", exception.getMessage(), requestId);
        } catch (SelfApprovalException exception) {
            return error(Response.Status.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN", exception.getMessage(), requestId);
        } catch (UnauthorizedApprovalException exception) {
            return error(Response.Status.FORBIDDEN, "NOT_AUTHORIZED_APPROVER", exception.getMessage(), requestId);
        } catch (EntitlementNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "ENTITLEMENT_NOT_FOUND", exception.getMessage(), requestId);
        } catch (EntitlementNotRequestableException exception) {
            return error(Response.Status.CONFLICT, "ENTITLEMENT_NOT_REQUESTABLE", exception.getMessage(), requestId);
        } catch (AccessPackageRequiredException exception) {
            return error(Response.Status.CONFLICT, "ACCESS_PACKAGE_REQUIRED", exception.getMessage(), requestId);
        } catch (InvalidRequestedDurationException exception) {
            return error(Response.Status.CONFLICT, "INVALID_REQUESTED_DURATION", exception.getMessage(), requestId);
        } catch (InvalidRequestStateException exception) {
            return error(Response.Status.CONFLICT, "INVALID_REQUEST_STATE", exception.getMessage(), requestId);
        } catch (ConcurrentRequestModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_MODIFICATION", exception.getMessage(), requestId);
        }
    }
}
