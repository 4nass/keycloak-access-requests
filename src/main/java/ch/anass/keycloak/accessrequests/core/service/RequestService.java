package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.grant.AccessGrant;
import ch.anass.keycloak.accessrequests.core.domain.grant.GrantOrigin;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.request.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.request.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.AccessPackage;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.DurationPolicy;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.request.InvalidProvisioningRetryException;
import ch.anass.keycloak.accessrequests.core.domain.grant.ProvisioningResult;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.request.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.approval.SelfApprovalException;
import ch.anass.keycloak.accessrequests.core.domain.approval.UnauthorizedApprovalException;
import ch.anass.keycloak.accessrequests.core.domain.request.UnauthorizedRequestActionException;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessGrantAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestNotificationPublisher;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import ch.anass.keycloak.accessrequests.core.port.ApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.DuplicatePendingRequestException;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.EntitlementProvisioner;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessPackageProvisioner;
import ch.anass.keycloak.accessrequests.core.port.AccessPackageRepository;
import ch.anass.keycloak.accessrequests.core.port.UserStatusReader;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RequestService {

    private static final Logger LOG = Logger.getLogger(RequestService.class.getName());
    private static final AccessRequestNotificationPublisher NO_OP_NOTIFICATION_PUBLISHER = notification -> {
    };
    private static final AccessPackageRepository NO_ACCESS_PACKAGES = new AccessPackageRepository() {
        @Override
        public void create(AccessPackage accessPackage) {
            throw new UnsupportedOperationException("access package creation requires a configured repository");
        }

        @Override
        public java.util.Optional<AccessPackage> findByEntitlementId(String realmId, String entitlementId) {
            return java.util.Optional.empty();
        }
    };
    private static final AccessPackageProvisioner NO_JIT_PROVISIONER = (realmId, requesterId, accessPackage) ->
            ProvisioningResult.failed(ProvisioningFailureCode.PROVIDER_UNAVAILABLE,
                    "No access package provisioner is configured.");
    private static final AccessGrantAuthorizer NO_PACKAGE_GRANT_AUTHORIZER = (realmId, requestId) -> {
        throw new IllegalStateException("Access package grant authorization is not configured");
    };

    private final EntitlementRepository entitlementRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final AccessGrantRepository accessGrantRepository;
    private final EffectiveAccessChecker effectiveAccessChecker;
    private final UserStatusReader userStatusReader;
    private final RequestPolicy requestPolicy;
    private final AccessRequestEventPublisher eventPublisher;
    private final AccessRequestNotificationPublisher notificationPublisher;
    private final AccessRequestNotificationPolicy notificationPolicy;
    private final ApprovalAuthorizer approvalAuthorizer;
    private final AccessRequestTransaction transaction;
    private final List<EntitlementProvisioner> provisioners;
    private final AccessPackageRepository accessPackages;
    private final AccessPackageProvisioner jitProvisioner;
    private final AccessGrantAuthorizer grantAuthorizer;
    private final boolean requireBoundPackage;
    private final Clock clock;

    public RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            AccessGrantRepository accessGrantRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy,
            AccessRequestEventPublisher eventPublisher,
            ApprovalAuthorizer approvalAuthorizer,
            AccessRequestTransaction transaction,
            List<EntitlementProvisioner> provisioners) {
        this(entitlementRepository, accessRequestRepository, accessGrantRepository, effectiveAccessChecker, userStatusReader,
                requestPolicy, eventPublisher, approvalAuthorizer, transaction, provisioners,
                NO_OP_NOTIFICATION_PUBLISHER, Clock.systemUTC());
    }

    public RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            AccessGrantRepository accessGrantRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy,
            AccessRequestEventPublisher eventPublisher,
            ApprovalAuthorizer approvalAuthorizer,
            AccessRequestTransaction transaction,
            List<EntitlementProvisioner> provisioners,
            Clock clock) {
        this(entitlementRepository, accessRequestRepository, accessGrantRepository, effectiveAccessChecker, userStatusReader,
                requestPolicy, eventPublisher, approvalAuthorizer, transaction, provisioners,
                NO_OP_NOTIFICATION_PUBLISHER, clock);
    }

    public RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            AccessGrantRepository accessGrantRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy,
            AccessRequestEventPublisher eventPublisher,
            ApprovalAuthorizer approvalAuthorizer,
            AccessRequestTransaction transaction,
            List<EntitlementProvisioner> provisioners,
            AccessRequestNotificationPublisher notificationPublisher) {
        this(entitlementRepository, accessRequestRepository, accessGrantRepository, effectiveAccessChecker, userStatusReader,
                requestPolicy, eventPublisher, approvalAuthorizer, transaction, provisioners, notificationPublisher,
                Clock.systemUTC());
    }

    public RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            AccessGrantRepository accessGrantRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy,
            AccessRequestEventPublisher eventPublisher,
            ApprovalAuthorizer approvalAuthorizer,
            AccessRequestTransaction transaction,
            List<EntitlementProvisioner> provisioners,
            AccessRequestNotificationPublisher notificationPublisher,
            Clock clock) {
        this(entitlementRepository, accessRequestRepository, accessGrantRepository, effectiveAccessChecker,
                userStatusReader, requestPolicy, eventPublisher, approvalAuthorizer, transaction, provisioners,
                notificationPublisher, clock, NO_ACCESS_PACKAGES, NO_JIT_PROVISIONER,
                NO_PACKAGE_GRANT_AUTHORIZER, false);
    }

    public RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            AccessGrantRepository accessGrantRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy,
            AccessRequestEventPublisher eventPublisher,
            ApprovalAuthorizer approvalAuthorizer,
            AccessRequestTransaction transaction,
            List<EntitlementProvisioner> provisioners,
            AccessRequestNotificationPublisher notificationPublisher,
            Clock clock,
            AccessPackageRepository accessPackages,
            AccessPackageProvisioner jitProvisioner,
            AccessGrantAuthorizer grantAuthorizer) {
        this(entitlementRepository, accessRequestRepository, accessGrantRepository, effectiveAccessChecker,
                userStatusReader, requestPolicy, eventPublisher, approvalAuthorizer, transaction, provisioners,
                notificationPublisher, clock, accessPackages, jitProvisioner, grantAuthorizer, true);
    }

    private RequestService(
            EntitlementRepository entitlementRepository,
            AccessRequestRepository accessRequestRepository,
            AccessGrantRepository accessGrantRepository,
            EffectiveAccessChecker effectiveAccessChecker,
            UserStatusReader userStatusReader,
            RequestPolicy requestPolicy,
            AccessRequestEventPublisher eventPublisher,
            ApprovalAuthorizer approvalAuthorizer,
            AccessRequestTransaction transaction,
            List<EntitlementProvisioner> provisioners,
            AccessRequestNotificationPublisher notificationPublisher,
            Clock clock,
            AccessPackageRepository accessPackages,
            AccessPackageProvisioner jitProvisioner,
            AccessGrantAuthorizer grantAuthorizer,
            boolean requireBoundPackage) {
        this.entitlementRepository = Objects.requireNonNull(entitlementRepository);
        this.accessRequestRepository = Objects.requireNonNull(accessRequestRepository);
        this.accessGrantRepository = Objects.requireNonNull(accessGrantRepository);
        this.effectiveAccessChecker = Objects.requireNonNull(effectiveAccessChecker);
        this.userStatusReader = Objects.requireNonNull(userStatusReader);
        this.requestPolicy = Objects.requireNonNull(requestPolicy);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.notificationPublisher = Objects.requireNonNull(notificationPublisher);
        this.notificationPolicy = new AccessRequestNotificationPolicy();
        this.approvalAuthorizer = Objects.requireNonNull(approvalAuthorizer);
        this.transaction = Objects.requireNonNull(transaction);
        this.provisioners = List.copyOf(Objects.requireNonNull(provisioners));
        this.accessPackages = Objects.requireNonNull(accessPackages);
        this.jitProvisioner = Objects.requireNonNull(jitProvisioner);
        this.grantAuthorizer = Objects.requireNonNull(grantAuthorizer);
        this.requireBoundPackage = requireBoundPackage;
        this.clock = Objects.requireNonNull(clock);
    }

    public AccessRequest create(
            String realmId,
            String requesterId,
            String entitlementId,
            String justification) {
        return create(realmId, requesterId, entitlementId, justification, null, false);
    }

    public AccessRequest create(
            String realmId,
            String requesterId,
            String entitlementId,
            String justification,
            Long durationSeconds,
            boolean permanent) {
        try {
            return transaction.execute(() -> {
                Entitlement entitlement = requireCurrentEntitlementForUpdate(realmId, entitlementId);
                requireBoundPackage(entitlement);
                Long selectedDuration = durationSeconds;
                if (!permanent && selectedDuration == null) {
                    selectedDuration = entitlement.durationPolicy().defaultDuration().getSeconds();
                }
                try {
                    entitlement.durationPolicy().validate(
                            selectedDuration == null ? null : Duration.ofSeconds(selectedDuration), permanent);
                    if (!permanent) {
                        DurationPolicy.expiryAt(Instant.now(clock), selectedDuration);
                    }
                } catch (IllegalArgumentException exception) {
                    throw new InvalidRequestedDurationException();
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
                        occurredAt,
                        selectedDuration,
                        permanent);
                AccessRequest persisted = accessRequestRepository.createIfNoPending(request)
                        .orElseThrow(() -> new RequestAlreadyPendingException(entitlementId));
                AccessRequestEvent event = AccessRequestEvent.created(persisted, requesterId, occurredAt);
                publish(event, persisted, entitlement);
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

    public AccessRequestPage findByRequester(AccessRequestQuery query) {
        return accessRequestRepository.findByRequester(query);
    }

    public AccessRequest approve(
            String realmId,
            String requestId,
            String approverId,
            String decisionComment) {
        return transaction.execute(() -> {
            AccessRequest request = findRequest(realmId, requestId);
            Entitlement entitlement = requireCurrentEntitlementForUpdate(realmId, request.entitlementId());
            authorizeDecision(realmId, request, approverId);
            requireBoundPackage(entitlement);
            AccessRequest candidate = request.copy();
            Instant decidedAt = Instant.now(clock);
            candidate.approve(approverId, decisionComment, decidedAt);
            requireRepresentableExpiry(candidate, decidedAt);
            AccessRequest approved = updateOrThrow(candidate, request.version());
            AccessRequestEvent approvalEvent = AccessRequestEvent.approved(
                    approved, approverId, decidedAt, decisionComment);
            publish(approvalEvent, approved, entitlement);
            publish(AccessRequestEvent.provisioningStarted(approved, approverId, decidedAt), approved, entitlement);

            AccessPackage accessPackage = accessPackages.findByEntitlementId(realmId, entitlement.id()).orElse(null);
            ProvisioningResult result = provision(approved.id(), realmId, approved.requesterId(), entitlement,
                    accessPackage);
            AccessRequest completed = approved.copy();
            Instant completedAt = Instant.now(clock);
            if (result.isSuccessful()) {
                completed.markProvisioningSucceeded(completedAt);
            } else {
                completed.markProvisioningFailed(completedAt);
            }
            AccessRequest persisted = updateOrThrow(completed, approved.version());
            if (result.isSuccessful()) {
                persistProvisionedGrant(persisted, entitlement, result.grantOrigin(), completedAt, accessPackage);
            }
            AccessRequestEvent provisioningEvent = result.isSuccessful()
                    ? AccessRequestEvent.provisioningSucceeded(persisted, approverId, completedAt)
                    : AccessRequestEvent.provisioningFailed(
                            persisted, approverId, completedAt, result.failureReason(), result.failureCode());
            publish(provisioningEvent, persisted, entitlement);
            return persisted;
        });
    }

    public AccessRequest retryProvisioning(String realmId, String requestId, String actorId) {
        return transaction.execute(() -> {
            AccessRequest request = accessRequestRepository.findByIdForUpdate(realmId, requestId)
                    .orElseThrow(() -> new RequestNotFoundException(requestId));
            if (request.decisionStatus() != DecisionStatus.APPROVED
                    || request.provisioningStatus() != ProvisioningStatus.FAILED
                    || request.provisioningFailureClosed()) {
                throw new InvalidProvisioningRetryException();
            }

            Entitlement entitlement = entitlementRepository.findByIdForUpdate(realmId, request.entitlementId())
                    .orElseThrow(() -> new EntitlementNotFoundException(request.entitlementId()));
            if (entitlement.resourceType() != request.resourceType()
                    || !entitlement.resourceId().equals(request.resourceId())) {
                throw new InvalidProvisioningRetryException();
            }
            requireBoundPackage(entitlement);

            Instant startedAt = Instant.now(clock);
            requireRepresentableExpiry(request, startedAt);
            publish(AccessRequestEvent.provisioningStarted(request, actorId, startedAt), request, entitlement);

            AccessPackage accessPackage = accessPackages.findByEntitlementId(realmId, entitlement.id()).orElse(null);
            ProvisioningResult result = provision(request.id(), realmId, request.requesterId(), entitlement,
                    accessPackage);
            Instant completedAt = Instant.now(clock);
            AccessRequest candidate = request.copy();
            candidate.completeProvisioningRetry(
                    result.isSuccessful() ? ProvisioningStatus.SUCCEEDED : ProvisioningStatus.FAILED,
                    completedAt);
            AccessRequest persisted = updateOrThrow(candidate, request.version());
            if (result.isSuccessful()) {
                persistProvisionedGrant(persisted, entitlement, result.grantOrigin(), completedAt, accessPackage);
            }
            AccessRequestEvent event = result.isSuccessful()
                    ? AccessRequestEvent.provisioningSucceeded(persisted, actorId, completedAt)
                    : AccessRequestEvent.provisioningFailed(
                            persisted, actorId, completedAt, result.failureReason(), result.failureCode());
            publish(event, persisted, entitlement);
            return persisted;
        });
    }

    private void persistProvisionedGrant(AccessRequest request, Entitlement entitlement, GrantOrigin origin,
            Instant completedAt, AccessPackage accessPackage) {
        AccessGrant grant = AccessGrant.from(request, entitlement, origin, completedAt, accessPackage);
        accessGrantRepository.create(grant);
        if (accessPackage != null && grant.origin() == GrantOrigin.CREATED_BY_EXTENSION
                && grant.expiresAt() != null) {
            grantAuthorizer.authorize(grant.realmId(), grant.requestId());
        }
    }

    private void requireBoundPackage(Entitlement entitlement) {
        if (!requireBoundPackage) {
            return;
        }
        AccessPackage accessPackage = accessPackages.findByEntitlementId(entitlement.realmId(), entitlement.id())
                .orElseThrow(AccessPackageRequiredException::new);
        if (entitlement.resourceType() != ResourceType.GROUP
                || !entitlement.resourceId().equals(accessPackage.groupId())) {
            throw new AccessPackageRequiredException();
        }
    }

    public AccessRequest closeFailedProvisioning(String realmId, String requestId, String actorId, String reason) {
        return transaction.execute(() -> {
            AccessRequest request = accessRequestRepository.findByIdForUpdate(realmId, requestId)
                    .orElseThrow(() -> new RequestNotFoundException(requestId));
            AccessRequest candidate = request.copy();
            Instant closedAt = Instant.now(clock);
            candidate.closeFailedProvisioning(actorId, reason, closedAt);
            AccessRequest persisted = updateOrThrow(candidate, request.version());
            AccessRequestEvent event = AccessRequestEvent.provisioningClosed(persisted, actorId, closedAt);
            eventPublisher.publish(event);
            entitlementRepository.findById(realmId, request.entitlementId())
                    .ifPresent(entitlement -> notificationPolicy.notificationsFor(persisted, entitlement, event)
                            .forEach(notificationPublisher::publish));
            return persisted;
        });
    }

    public AccessRequest reject(
            String realmId,
            String requestId,
            String approverId,
            String decisionComment) {
        return transaction.execute(() -> {
            AccessRequest request = findRequest(realmId, requestId);
            Entitlement entitlement = entitlementRepository.findById(realmId, request.entitlementId())
                    .orElseThrow(() -> new EntitlementNotFoundException(request.entitlementId()));
            authorizeDecision(realmId, request, approverId);
            AccessRequest candidate = request.copy();
            Instant occurredAt = Instant.now(clock);
            candidate.reject(approverId, decisionComment, occurredAt);
            AccessRequest persisted = updateOrThrow(candidate, request.version());
            AccessRequestEvent event = AccessRequestEvent.rejected(
                    persisted, approverId, occurredAt, decisionComment);
            publish(event, persisted, entitlement);
            return persisted;
        });
    }

    private void publish(AccessRequestEvent event, AccessRequest request, Entitlement entitlement) {
        eventPublisher.publish(event);
        notificationPolicy.notificationsFor(request, entitlement, event).forEach(notificationPublisher::publish);
    }

    private static void requireRepresentableExpiry(AccessRequest request, Instant activatedAt) {
        if (!request.permanent() && request.requestedDurationSeconds() != null) {
            try {
                DurationPolicy.expiryAt(activatedAt, request.requestedDurationSeconds());
            } catch (IllegalArgumentException exception) {
                throw new InvalidRequestedDurationException();
            }
        }
    }

    private AccessRequest findRequest(String realmId, String requestId) {
        return accessRequestRepository.findById(realmId, requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
    }

    private void authorizeDecision(String realmId, AccessRequest request, String actorId) {
        if (request.requesterId().equals(actorId)) {
            throw new SelfApprovalException();
        }
        if (!approvalAuthorizer.canDecide(realmId, actorId, request.entitlementId())) {
            throw new UnauthorizedApprovalException();
        }
    }

    private Entitlement requireCurrentEntitlementForUpdate(String realmId, String entitlementId) {
        Entitlement entitlement = entitlementRepository.findByIdForUpdate(realmId, entitlementId)
                .orElseThrow(() -> new EntitlementNotFoundException(entitlementId));
        if (!realmId.equals(entitlement.realmId())) {
            throw new EntitlementNotFoundException(entitlementId);
        }
        if (!entitlement.requestable()) {
            throw new EntitlementNotRequestableException(entitlementId);
        }
        return entitlement;
    }

    private ProvisioningResult provision(String requestId, String realmId, String requesterId, Entitlement entitlement,
            AccessPackage accessPackage) {
        if (requireBoundPackage && (accessPackage == null
                || entitlement.resourceType() != ResourceType.GROUP
                || !entitlement.resourceId().equals(accessPackage.groupId()))) {
            throw new AccessPackageRequiredException();
        }
        if (accessPackage != null) {
            if (!entitlement.realmId().equals(accessPackage.realmId())
                    || !entitlement.id().equals(accessPackage.entitlementId())) {
                throw new IllegalStateException("The access package does not match the locked entitlement");
            }
            try {
                ProvisioningResult result = jitProvisioner.grant(realmId, requesterId, accessPackage);
                return result == null ? ProvisioningResult.failed(ProvisioningFailureCode.PROVIDER_UNAVAILABLE,
                        "The access package provisioner returned no result.") : result;
            } catch (RuntimeException exception) {
                LOG.log(Level.SEVERE, "Unexpected access package provisioning failure [requestId=" + requestId
                        + ", realmId=" + realmId + ", entitlementId=" + entitlement.id() + "]", exception);
                return ProvisioningResult.failed(ProvisioningFailureCode.UNEXPECTED_FAILURE,
                        "The access package provisioner failed.");
            }
        }
        for (EntitlementProvisioner provisioner : provisioners) {
            if (!provisioner.supports(entitlement.resourceType())) {
                continue;
            }
            try {
                ProvisioningResult result = provisioner.grant(realmId, requesterId, entitlement);
                return result == null
                        ? ProvisioningResult.failed(
                                ProvisioningFailureCode.PROVIDER_UNAVAILABLE,
                                "The entitlement provisioner returned no result.")
                        : result;
            } catch (RuntimeException exception) {
                LOG.log(Level.SEVERE,
                        "Unexpected provisioning failure [requestId=" + requestId
                        + ", realmId=" + realmId
                        + ", entitlementId=" + entitlement.id()
                        + ", provisioner=" + provisioner.getClass().getName() + "]",
                        exception);
                return ProvisioningResult.failed(ProvisioningFailureCode.UNEXPECTED_FAILURE,
                        "The entitlement provisioner failed: "
                        + exception.getClass().getSimpleName());
            }
        }
        return ProvisioningResult.failed(ProvisioningFailureCode.PROVIDER_UNAVAILABLE,
                "No entitlement provisioner supports resource type " + entitlement.resourceType() + ".");
    }

    private AccessRequest updateOrThrow(AccessRequest candidate, long expectedVersion) {
        return accessRequestRepository.updateIfVersionMatches(candidate, expectedVersion)
                .orElseThrow(() -> new ConcurrentRequestModificationException(candidate.id()));
    }
}
