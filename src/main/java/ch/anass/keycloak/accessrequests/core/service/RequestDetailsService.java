package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestDetails;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestHistoryReader;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;

import java.util.Objects;

/**
 * Loads requester-visible request details without allowing cross-user enumeration.
 */
public final class RequestDetailsService {

    private final AccessRequestRepository accessRequestRepository;
    private final AccessRequestHistoryReader historyReader;

    public RequestDetailsService(
            AccessRequestRepository accessRequestRepository,
            AccessRequestHistoryReader historyReader) {
        this.accessRequestRepository = Objects.requireNonNull(accessRequestRepository);
        this.historyReader = Objects.requireNonNull(historyReader);
    }

    public AccessRequestDetails findForRequester(String realmId, String requesterId, String requestId) {
        AccessRequest request = accessRequestRepository.findById(realmId, requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
        if (!request.requesterId().equals(requesterId)) {
            throw new RequestNotFoundException(requestId);
        }
        return new AccessRequestDetails(request, historyReader.findByRequestId(realmId, requestId));
    }
}
