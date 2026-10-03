package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestDetails;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.request.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.request.UnauthorizedRequestActionException;
import ch.anass.keycloak.accessrequests.core.service.AccessAlreadyGrantedException;
import ch.anass.keycloak.accessrequests.core.service.AccessPackageRequiredException;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.EntitlementNotRequestableException;
import ch.anass.keycloak.accessrequests.core.service.InvalidJustificationException;
import ch.anass.keycloak.accessrequests.core.service.InvalidRequestedDurationException;
import ch.anass.keycloak.accessrequests.core.service.ConcurrentRequestModificationException;
import ch.anass.keycloak.accessrequests.core.service.RequestAlreadyPendingException;
import ch.anass.keycloak.accessrequests.core.service.RequestNotFoundException;
import ch.anass.keycloak.accessrequests.core.service.UserDisabledException;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.RequestDetailResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.RequestListResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.RequestResponse;
import ch.anass.keycloak.accessrequests.spi.realm.dto.RequestDto.RequestSubmission;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import static ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestErrors.error;
import static ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestQueryParameters.parseDecisionStatus;
import static ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestQueryParameters.parseInstant;
import static ch.anass.keycloak.accessrequests.spi.realm.resource.AccessRequestQueryParameters.parseResourceType;

final class AccessRequestRequesterHandler extends AccessRequestHandlerSupport {

    AccessRequestRequesterHandler(AccessRequestServiceFactory services) {
        super(services);
    }

    public Response submitRequest(RequestSubmission submission) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        RequestSubmission validatedSubmission = requireSubmission(submission);
        try {
            AccessRequest created = requestService(authenticatedRequest).create(
                    authenticatedRequest.realm().getId(),
                    authenticatedRequest.user().getId(),
                    validatedSubmission.entitlementId(),
                    validatedSubmission.justification(),
                    validatedSubmission.durationSeconds(),
                    Boolean.TRUE.equals(validatedSubmission.permanent()));
            return Response.status(Response.Status.CREATED)
                    .entity(RequestResponse.from(created))
                    .build();
        } catch (InvalidJustificationException | InvalidRequestedDurationException exception) {
            throw new BadRequestException(exception.getMessage(), exception);
        } catch (EntitlementNotFoundException exception) {
            throw new NotFoundException(exception.getMessage(), exception);
        } catch (AccessPackageRequiredException exception) {
            return error(Response.Status.CONFLICT, "ACCESS_PACKAGE_REQUIRED", exception.getMessage(), null);
        } catch (EntitlementNotRequestableException | AccessAlreadyGrantedException
                 | RequestAlreadyPendingException exception) {
            throw new ClientErrorException(Response.Status.CONFLICT, exception);
        } catch (UserDisabledException exception) {
            throw new ForbiddenException(exception.getMessage(), exception);
        }
    }

    public Response listRequests(
            String decisionStatus,
            String resourceType,
            String from,
            String to,
            int page,
            int size) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            AccessRequestPage requestPage = requestService(authenticatedRequest).findByRequester(
                    new AccessRequestQuery(
                            authenticatedRequest.realm().getId(),
                            authenticatedRequest.user().getId(),
                            parseDecisionStatus(decisionStatus),
                            parseResourceType(resourceType),
                            parseInstant(from, "from"),
                            parseInstant(to, "to"),
                            page,
                            size));
            return Response.ok(RequestListResponse.from(requestPage)).build();
        } catch (IllegalArgumentException exception) {
            return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST_QUERY", exception.getMessage(), null);
        }
    }

    public Response requestDetails(String requestId) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            AccessRequestDetails details = requestDetailsService().findForRequester(
                    authenticatedRequest.realm().getId(), authenticatedRequest.user().getId(), requestId);
            return Response.ok(RequestDetailResponse.from(details)).build();
        } catch (RequestNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "REQUEST_NOT_FOUND", exception.getMessage(), requestId);
        }
    }

    public Response cancelRequest(String requestId) {
        AuthenticatedRequest authenticatedRequest = authenticate();
        try {
            requestService(authenticatedRequest).cancel(
                    authenticatedRequest.realm().getId(), requestId, authenticatedRequest.user().getId());
            return Response.noContent().build();
        } catch (RequestNotFoundException exception) {
            return error(Response.Status.NOT_FOUND, "REQUEST_NOT_FOUND", exception.getMessage(), requestId);
        } catch (UnauthorizedRequestActionException exception) {
            return error(Response.Status.FORBIDDEN, "REQUEST_CANCELLATION_FORBIDDEN", exception.getMessage(), requestId);
        } catch (InvalidRequestStateException exception) {
            return error(Response.Status.CONFLICT, "INVALID_REQUEST_STATE", exception.getMessage(), requestId);
        } catch (ConcurrentRequestModificationException exception) {
            return error(Response.Status.CONFLICT, "CONCURRENT_MODIFICATION", exception.getMessage(), requestId);
        }
    }

    private static RequestSubmission requireSubmission(RequestSubmission submission) {
        if (submission == null
                || submission.entitlementId() == null
                || submission.entitlementId().isBlank()
                || submission.justification() == null) {
            throw new BadRequestException("entitlementId and justification must be provided");
        }
        return submission;
    }
}
