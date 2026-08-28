package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.UnauthorizedRequestActionException;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import ch.anass.keycloak.accessrequests.core.port.ApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.DuplicatePendingRequestException;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.UserStatusReader;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class RequestService {

    private final EntitlementRepository entitlementRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final EffectiveAccessChecker effectiveAccessChecker;
    private final UserStatusReader userStatusReader;
    private final RequestPolicy requestPolicy;
    private final AccessRequestEventPublisher eventPublisher;
    private final ApprovalAuthorizer approvalAuthorizer;
    private final AccessRequestTransaction transaction;
    private final Clock clock;

    public RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy,
            AccessRequestEventPublisher eventPublisher,
            ApprovalAuthorizer approvalAuthorizer,
            AccessRequestTransaction transaction) {
        this(entitlementRepository, accessRequestRepository, effectiveAccessChecker, userStatusReader,
                requestPolicy, eventPublisher, approvalAuthorizer, transaction, Clock.systemUTC());
    }

    public RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy,
            AccessRequestEventPublisher eventPublisher,
            ApprovalAuthorizer approvalAuthorizer,
            AccessRequestTransaction transaction,
            Clock clock) {
        this.entitlementRepository = Objects.requireNonNull(entitlementRepository);
        this.accessRequestRepository = Objects.requireNonNull(accessRequestRepository);
        this.effectiveAccessChecker = Objects.requireNonNull(effectiveAccessChecker);
        this.userStatusReader = Objects.requireNonNull(userStatusReader);
        this.requestPolicy = Objects.requireNonNull(requestPolicy);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.approvalAuthorizer = Objects.requireNonNull(approvalAuthorizer);
        this.transaction = Objects.requireNonNull(transaction);
        this.clock = Objects.requireNonNull(clock);
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
        Instant occurredAt = Instant.now(clock);
        AccessRequest request = AccessRequest.create(
                UUID.randomUUID().toString(),
                realmId,
                requesterId,
                entitlement.id(),
                entitlement.resourceType(),
                entitlement.resourceId(),
                entitlement.displayName(),
                justification,
                occurredAt);
        try {
            return transaction.execute(() -> {
                AccessRequest persisted = accessRequestRepository.createIfNoPending(request)
                        .orElseThrow(() -> new RequestAlreadyPendingException(entitlementId));
                eventPublisher.publish(AccessRequestEvent.created(persisted, requesterId, occurredAt));
                return persisted;
            });
        } catch (DuplicatePendingRequestException exception) {
            throw new RequestAlreadyPendingException(entitlementId);
        }
    }

    public void cancel(String realmId, String requestId, String actorId) {
        AccessRequest request = accessRequestRepository.findById(realmId, requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
        transaction.execute(() -> {
            AccessRequest candidate = request.copy();
            Instant occurredAt = Instant.now(clock);
            candidate.cancel(actorId, occurredAt);
            AccessRequest persisted = accessRequestRepository
                    .updateIfVersionMatches(candidate, request.version())
                    .orElseThrow(() -> new ConcurrentRequestModificationException(requestId));
            eventPublisher.publish(AccessRequestEvent.canceled(persisted, actorId, occurredAt));
            return persisted;
        });
    }

    public AccessRequest approve(
            String realmId,
            String requestId,
            String approverId,
            String decisionComment) {
        AccessRequest request = findRequest(realmId, requestId);
        authorizeDecision(realmId, request, approverId);
        requireCurrentEntitlement(realmId, request.entitlementId());

        return transaction.execute(() -> {
            AccessRequest candidate = request.copy();
            Instant occurredAt = Instant.now(clock);
            candidate.approve(approverId, decisionComment, occurredAt);
            AccessRequest persisted = updateOrThrow(candidate, request.version());
            eventPublisher.publish(AccessRequestEvent.approved(
                    persisted, approverId, occurredAt, decisionComment));
            return persisted;
        });
    }

    public AccessRequest reject(
            String realmId,
            String requestId,
            String approverId,
            String decisionComment) {
        AccessRequest request = findRequest(realmId, requestId);
        authorizeDecision(realmId, request, approverId);

        return transaction.execute(() -> {
            AccessRequest candidate = request.copy();
            Instant occurredAt = Instant.now(clock);
            candidate.reject(approverId, decisionComment, occurredAt);
            AccessRequest persisted = updateOrThrow(candidate, request.version());
            eventPublisher.publish(AccessRequestEvent.rejected(
                    persisted, approverId, occurredAt, decisionComment));
            return persisted;
        });
    }

    private AccessRequest findRequest(String realmId, String requestId) {
        return accessRequestRepository.findById(realmId, requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
    }

    private void authorizeDecision(String realmId, AccessRequest request, String actorId) {
        if (request.requesterId().equals(actorId)) {
            throw new UnauthorizedRequestActionException("A requester cannot decide their own request.");
        }
        if (!approvalAuthorizer.canDecide(realmId, actorId, request.entitlementId())) {
            throw new UnauthorizedRequestActionException("The actor cannot decide this request.");
        }
    }

    private void requireCurrentEntitlement(String realmId, String entitlementId) {
        Entitlement entitlement = entitlementRepository.findById(realmId, entitlementId)
                .orElseThrow(() -> new EntitlementNotFoundException(entitlementId));
        if (!entitlement.requestable()) {
            throw new EntitlementNotRequestableException(entitlementId);
        }
    }

    private AccessRequest updateOrThrow(AccessRequest candidate, long expectedVersion) {
        return accessRequestRepository.updateIfVersionMatches(candidate, expectedVersion)
                .orElseThrow(() -> new ConcurrentRequestModificationException(candidate.id()));
    }
}
