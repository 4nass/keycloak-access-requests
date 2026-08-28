package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.UserStatusReader;

import java.util.Objects;
import java.util.UUID;

public final class RequestService {

    private final EntitlementRepository entitlementRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final EffectiveAccessChecker effectiveAccessChecker;
    private final UserStatusReader userStatusReader;
    private final RequestPolicy requestPolicy;

    public RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy) {
        this.entitlementRepository = Objects.requireNonNull(entitlementRepository);
        this.accessRequestRepository = Objects.requireNonNull(accessRequestRepository);
        this.effectiveAccessChecker = Objects.requireNonNull(effectiveAccessChecker);
        this.userStatusReader = Objects.requireNonNull(userStatusReader);
        this.requestPolicy = Objects.requireNonNull(requestPolicy);
    }

    public AccessRequest create(
            String realmId,
            String requesterId,
            String entitlementId,
            String justification) {
        Entitlement entitlement = entitlementRepository.findById(realmId, entitlementId)
                .orElseThrow(() -> new EntitlementNotFoundException(entitlementId));

        if (!realmId.equals(entitlement.realmId())) {
            throw new EntitlementNotFoundException(entitlementId);
        }
        if (!entitlement.requestable()) {
            throw new EntitlementNotRequestableException(entitlementId);
        }
        if (!userStatusReader.isEnabled(realmId, requesterId)) {
            throw new UserDisabledException(requesterId);
        }

        requestPolicy.validateJustification(justification);

        if (effectiveAccessChecker.hasAccess(realmId, requesterId, entitlement)) {
            throw new AccessAlreadyGrantedException(entitlementId);
        }
        if (accessRequestRepository.existsPending(realmId, requesterId, entitlementId)) {
            throw new RequestAlreadyPendingException(entitlementId);
        }

        AccessRequest request = AccessRequest.create(
                UUID.randomUUID().toString(),
                realmId,
                requesterId,
                entitlement.id(),
                entitlement.resourceType(),
                entitlement.resourceId(),
                entitlement.displayName(),
                justification);
        return accessRequestRepository.save(request);
    }

    public void cancel(String realmId, String requestId, String actorId) {
        AccessRequest request = accessRequestRepository.findById(realmId, requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
        request.cancel(actorId);
        accessRequestRepository.save(request);
    }
}
