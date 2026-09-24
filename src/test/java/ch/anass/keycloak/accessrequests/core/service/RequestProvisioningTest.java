package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestPage;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestQuery;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueuePage;
import ch.anass.keycloak.accessrequests.core.domain.ApprovalQueueQuery;
import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.DecisionStatus;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.InvalidProvisioningRetryException;
import ch.anass.keycloak.accessrequests.core.domain.InvalidProvisioningClosureException;
import ch.anass.keycloak.accessrequests.core.domain.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningResult;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestEventPublisher;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestRepository;
import ch.anass.keycloak.accessrequests.core.port.AccessRequestTransaction;
import ch.anass.keycloak.accessrequests.core.port.ApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.EffectiveAccessChecker;
import ch.anass.keycloak.accessrequests.core.port.EntitlementProvisioner;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import ch.anass.keycloak.accessrequests.core.port.UserStatusReader;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestProvisioningTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void provisionsAnApprovedRequestAndRecordsTheSuccessfulDelivery() {
        Fixture fixture = fixture(ResourceType.REALM_ROLE, ProvisioningOutcome.SUCCEEDED);

        AccessRequest approved = fixture.service().approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved for the project.");

        assertEquals(DecisionStatus.APPROVED, approved.decisionStatus());
        assertEquals(ProvisioningStatus.SUCCEEDED, approved.provisioningStatus());
        assertEquals(fixture.request().realmId(), fixture.provisioner().realmId());
        assertEquals(fixture.request().requesterId(), fixture.provisioner().requesterId());
        assertEquals(fixture.entitlement(), fixture.provisioner().entitlement());
        assertEquals(List.of(
                        "REQUEST_APPROVED",
                        "PROVISIONING_STARTED",
                        "PROVISIONING_SUCCEEDED"),
                fixture.eventTypes());
        assertEquals(ProvisioningStatus.SUCCEEDED, fixture.persistedRequest().provisioningStatus());
    }

    @Test
    void recordsAProvisioningFailureWithoutReversingTheApprovalDecision() {
        Fixture fixture = fixture(ResourceType.CLIENT_ROLE, ProvisioningOutcome.FAILED);

        AccessRequest approved = fixture.service().approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved for the project.");

        assertEquals(DecisionStatus.APPROVED, approved.decisionStatus());
        assertEquals(ProvisioningStatus.FAILED, approved.provisioningStatus());
        assertEquals(1, fixture.provisioner().grantAttempts());
        assertEquals(List.of(
                        "REQUEST_APPROVED",
                        "PROVISIONING_STARTED",
                        "PROVISIONING_FAILED"),
                fixture.eventTypes());
        assertEquals(DecisionStatus.APPROVED, fixture.persistedRequest().decisionStatus());
        assertEquals(ProvisioningStatus.FAILED, fixture.persistedRequest().provisioningStatus());
    }

    @Test
    void supportsEveryV0ResourceTypeThroughAProvisioner() {
        for (ResourceType resourceType : ResourceType.values()) {
            Fixture fixture = fixture(resourceType, ProvisioningOutcome.SUCCEEDED);

            AccessRequest approved = fixture.service().approve(
                    fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved.");

            assertEquals(ProvisioningStatus.SUCCEEDED, approved.provisioningStatus());
            assertEquals(resourceType, fixture.provisioner().entitlement().resourceType());
        }
    }

    @Test
    void doesNotProvisionTwiceWhenAnApprovalIsRepeated() {
        Fixture fixture = fixture(ResourceType.GROUP, ProvisioningOutcome.SUCCEEDED);

        fixture.service().approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved for the project.");

        assertThrows(InvalidRequestStateException.class, () -> fixture.service().approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved again."));
        assertEquals(1, fixture.provisioner().grantAttempts());
        assertEquals(List.of(
                        "REQUEST_APPROVED",
                        "PROVISIONING_STARTED",
                        "PROVISIONING_SUCCEEDED"),
                fixture.eventTypes());
    }

    @Test
    void logsUnexpectedProvisionerExceptionsForApprovalAndRetryWithoutPersistingTheirMessage() {
        Fixture fixture = fixture(ResourceType.REALM_ROLE,
                List.of(ProvisioningOutcome.THROWS, ProvisioningOutcome.THROWS));
        List<LogRecord> records = captureProvisioningLogs(() -> {
            assertEquals(ProvisioningStatus.FAILED, fixture.service().approve(
                    fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved.")
                    .provisioningStatus());
            assertEquals(ProvisioningStatus.FAILED, fixture.service().retryProvisioning(
                    fixture.request().realmId(), fixture.request().id(), "realm-admin-1")
                    .provisioningStatus());
        });

        assertEquals(2, records.size());
        for (LogRecord record : records) {
            assertEquals(Level.SEVERE, record.getLevel());
            assertTrue(record.getMessage().contains("requestId=request-1"));
            assertTrue(record.getMessage().contains("realmId=realm-1"));
            assertTrue(record.getMessage().contains("entitlementId=entitlement-1"));
            assertEquals("Sensitive provider diagnostic", record.getThrown().getMessage());
        }
        assertEquals(List.of("UNEXPECTED_FAILURE", "UNEXPECTED_FAILURE"), fixture.events().published().stream()
                .filter(event -> event.type().name().equals("PROVISIONING_FAILED"))
                .map(AccessRequestEvent::metadata)
                .toList());
        assertTrue(fixture.events().published().stream()
                .noneMatch(event -> event.comment() != null
                        && event.comment().contains("Sensitive provider diagnostic")));
    }

    @Test
    void retriesAfterATransientProvisionerExceptionHasCleared() {
        Fixture fixture = fixture(ResourceType.REALM_ROLE,
                List.of(ProvisioningOutcome.THROWS, ProvisioningOutcome.SUCCEEDED));
        List<LogRecord> records = captureProvisioningLogs(() -> {
            AccessRequest approved = fixture.service().approve(
                    fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved.");
            assertEquals(ProvisioningStatus.FAILED, approved.provisioningStatus());
            AccessRequest retried = fixture.service().retryProvisioning(
                    fixture.request().realmId(), fixture.request().id(), "realm-admin-1");
            assertEquals(ProvisioningStatus.SUCCEEDED, retried.provisioningStatus());
        });

        assertEquals(1, records.size());
        assertEquals("Sensitive provider diagnostic", records.getFirst().getThrown().getMessage());
        assertEquals(2, fixture.provisioner().grantAttempts());
        assertEquals(ProvisioningStatus.SUCCEEDED, fixture.persistedRequest().provisioningStatus());
        assertEquals(List.of("REQUEST_APPROVED", "PROVISIONING_STARTED", "PROVISIONING_FAILED",
                "PROVISIONING_STARTED", "PROVISIONING_SUCCEEDED"), fixture.eventTypes());
        assertEquals(List.of("UNEXPECTED_FAILURE"), fixture.events().published().stream()
                .filter(event -> event.type().name().equals("PROVISIONING_FAILED"))
                .map(AccessRequestEvent::metadata)
                .toList());
    }

    @Test
    void retriesOnlyTheProvisioningOfAnApprovedFailedRequestAndPreservesItsDecision() {
        Fixture fixture = fixture(
                ResourceType.CLIENT_ROLE,
                List.of(ProvisioningOutcome.FAILED, ProvisioningOutcome.SUCCEEDED));
        AccessRequest originallyApproved = fixture.service().approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved for the project.");

        AccessRequest retried = fixture.service().retryProvisioning(
                fixture.request().realmId(), fixture.request().id(), "realm-admin-1");

        assertEquals(DecisionStatus.APPROVED, retried.decisionStatus());
        assertEquals(ProvisioningStatus.SUCCEEDED, retried.provisioningStatus());
        assertEquals(originallyApproved.approverId(), retried.approverId());
        assertEquals(originallyApproved.decisionComment(), retried.decisionComment());
        assertEquals(originallyApproved.decidedAt(), retried.decidedAt());
        assertEquals(2, fixture.provisioner().grantAttempts());
        assertEquals(List.of(
                        "REQUEST_APPROVED",
                        "PROVISIONING_STARTED",
                        "PROVISIONING_FAILED",
                        "PROVISIONING_STARTED",
                        "PROVISIONING_SUCCEEDED"),
                fixture.eventTypes());
        assertEquals(List.of(1L, 1L, 2L, 2L, 3L), fixture.events().published().stream()
                .map(AccessRequestEvent::requestVersion).toList());
        assertEquals(ProvisioningStatus.SUCCEEDED, fixture.persistedRequest().provisioningStatus());
    }

    @Test
    void keepsAnApprovedRequestFailedWhenAnExplicitRetryAlsoFails() {
        Fixture fixture = fixture(
                ResourceType.REALM_ROLE,
                List.of(ProvisioningOutcome.FAILED, ProvisioningOutcome.FAILED));
        AccessRequest originallyApproved = fixture.service().approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved.");

        AccessRequest retried = fixture.service().retryProvisioning(
                fixture.request().realmId(), fixture.request().id(), "realm-admin-1");

        assertEquals(DecisionStatus.APPROVED, retried.decisionStatus());
        assertEquals(ProvisioningStatus.FAILED, retried.provisioningStatus());
        assertEquals(originallyApproved.decidedAt(), retried.decidedAt());
        assertEquals(2, fixture.provisioner().grantAttempts());
        assertEquals(List.of(
                        "REQUEST_APPROVED",
                        "PROVISIONING_STARTED",
                        "PROVISIONING_FAILED",
                        "PROVISIONING_STARTED",
                        "PROVISIONING_FAILED"),
                fixture.eventTypes());
        assertEquals(List.of("RESOURCE_MISSING", "RESOURCE_MISSING"), fixture.events().published().stream()
                .filter(event -> event.type().name().equals("PROVISIONING_FAILED"))
                .map(event -> event.metadata())
                .toList());
        assertEquals(List.of(originallyApproved.version(), retried.version()), fixture.events().published().stream()
                .filter(event -> event.type().name().equals("PROVISIONING_FAILED"))
                .map(event -> event.requestVersion())
                .toList());
        assertEquals(ProvisioningStatus.FAILED, fixture.persistedRequest().provisioningStatus());
    }

    @Test
    void rejectsRetryWhenProvisioningHasNotFailed() {
        Fixture pending = fixture(ResourceType.REALM_ROLE, ProvisioningOutcome.SUCCEEDED);
        assertRetryRejected(pending);
        assertEquals(0, pending.provisioner().grantAttempts());

        Fixture alreadyProvisioned = fixture(ResourceType.CLIENT_ROLE, ProvisioningOutcome.SUCCEEDED);
        alreadyProvisioned.service().approve(
                alreadyProvisioned.request().realmId(),
                alreadyProvisioned.request().id(),
                "approver-1",
                "Approved.");

        assertRetryRejected(alreadyProvisioned);
        assertEquals(1, alreadyProvisioned.provisioner().grantAttempts());
    }

    @Test
    void rejectsRetryForRejectedAndCanceledRequests() {
        Fixture rejected = fixture(ResourceType.REALM_ROLE, ProvisioningOutcome.SUCCEEDED);
        rejected.service().reject(
                rejected.request().realmId(), rejected.request().id(), "approver-1", "Not required.");

        assertRetryRejected(rejected);
        assertEquals(0, rejected.provisioner().grantAttempts());

        Fixture canceled = fixture(ResourceType.GROUP, ProvisioningOutcome.SUCCEEDED);
        canceled.service().cancel(canceled.request().realmId(), canceled.request().id(), canceled.request().requesterId());

        assertRetryRejected(canceled);
        assertEquals(0, canceled.provisioner().grantAttempts());
    }

    @Test
    void doesNotFindARequestOutsideItsRealmOrWhenItDoesNotExist() {
        Fixture fixture = fixture(ResourceType.REALM_ROLE, ProvisioningOutcome.FAILED);

        assertThrows(RequestNotFoundException.class, () -> fixture.service().retryProvisioning(
                "another-realm", fixture.request().id(), "realm-admin-1"));
        assertThrows(RequestNotFoundException.class, () -> fixture.service().retryProvisioning(
                fixture.request().realmId(), "missing-request", "realm-admin-1"));
        assertEquals(0, fixture.provisioner().grantAttempts());
    }

    @Test
    void allowsRetryAfterEntitlementIsUnpublishedButRejectsAChangedTarget() {
        Fixture unpublishedFixture = fixture(
                ResourceType.REALM_ROLE,
                List.of(ProvisioningOutcome.FAILED, ProvisioningOutcome.SUCCEEDED));
        unpublishedFixture.service().approve(
                unpublishedFixture.request().realmId(),
                unpublishedFixture.request().id(),
                "approver-1",
                "Approved.");
        Entitlement unpublished = unpublishedFixture.entitlement().unpublish(CLOCK.instant());
        RequestService retryService = provisioningEnabledService(
                unpublished,
                unpublishedFixture.requests(),
                unpublishedFixture.events(),
                unpublishedFixture.provisioner());

        AccessRequest retried = retryService.retryProvisioning(
                unpublishedFixture.request().realmId(), unpublishedFixture.request().id(), "realm-admin-1");

        assertEquals(ProvisioningStatus.SUCCEEDED, retried.provisioningStatus());
        assertEquals(2, unpublishedFixture.provisioner().grantAttempts());

        Fixture changedTargetFixture = fixture(
                ResourceType.REALM_ROLE,
                List.of(ProvisioningOutcome.FAILED, ProvisioningOutcome.SUCCEEDED));
        changedTargetFixture.service().approve(
                changedTargetFixture.request().realmId(),
                changedTargetFixture.request().id(),
                "approver-1",
                "Approved.");
        Entitlement changedTarget = Entitlement.rehydrate(
                changedTargetFixture.entitlement().id(),
                changedTargetFixture.entitlement().realmId(),
                changedTargetFixture.entitlement().resourceType(),
                "different-resource",
                changedTargetFixture.entitlement().displayName(),
                changedTargetFixture.entitlement().description(),
                changedTargetFixture.entitlement().riskLevel(),
                changedTargetFixture.entitlement().approverRoleId(),
                true,
                changedTargetFixture.entitlement().createdAt(),
                CLOCK.instant(),
                changedTargetFixture.entitlement().version());
        RequestService changedTargetService = provisioningEnabledService(
                changedTarget,
                changedTargetFixture.requests(),
                changedTargetFixture.events(),
                changedTargetFixture.provisioner());

        assertThrows(InvalidProvisioningRetryException.class, () -> changedTargetService.retryProvisioning(
                changedTargetFixture.request().realmId(),
                changedTargetFixture.request().id(),
                "realm-admin-1"));
        assertEquals(1, changedTargetFixture.provisioner().grantAttempts());
    }

    private static void assertRetryRejected(Fixture fixture) {
        assertThrows(InvalidProvisioningRetryException.class, () -> fixture.service().retryProvisioning(
                fixture.request().realmId(), fixture.request().id(), "realm-admin-1"));
    }

    @Test
    void closesAnUnrecoverableFailureWithAnAuditEventAndNoFurtherRetry() {
        Fixture fixture = fixture(ResourceType.REALM_ROLE, ProvisioningOutcome.FAILED);
        fixture.service().approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved.");

        AccessRequest closed = fixture.service().closeFailedProvisioning(
                fixture.request().realmId(), fixture.request().id(), "manager-1",
                "  The requester was permanently removed.  ");

        assertEquals(DecisionStatus.APPROVED, closed.decisionStatus());
        assertEquals(ProvisioningStatus.FAILED, closed.provisioningStatus());
        assertEquals("manager-1", closed.provisioningClosedBy());
        assertEquals("The requester was permanently removed.", closed.provisioningClosureReason());
        assertEquals(CLOCK.instant(), closed.provisioningClosedAt());
        assertTrue(fixture.persistedRequest().provisioningFailureClosed());
        assertEquals("PROVISIONING_CLOSED", fixture.eventTypes().getLast());
        assertEquals("The requester was permanently removed.", fixture.events().published().getLast().comment());
        assertEquals(closed.version(), fixture.events().published().getLast().requestVersion());
        assertThrows(InvalidProvisioningRetryException.class, () -> fixture.service().retryProvisioning(
                fixture.request().realmId(), fixture.request().id(), "manager-1"));
        assertThrows(InvalidProvisioningClosureException.class, () -> fixture.service().closeFailedProvisioning(
                fixture.request().realmId(), fixture.request().id(), "manager-1", "Another closure reason."));
        assertEquals(1, fixture.provisioner().grantAttempts());
    }

    @Test
    void rejectsClosureOfNonFailedAndCrossRealmRequests() {
        Fixture fixture = fixture(ResourceType.REALM_ROLE, ProvisioningOutcome.FAILED);
        assertThrows(InvalidProvisioningClosureException.class, () -> fixture.service().closeFailedProvisioning(
                fixture.request().realmId(), fixture.request().id(), "manager-1", "An operational reason."));
        assertThrows(RequestNotFoundException.class, () -> fixture.service().closeFailedProvisioning(
                "other-realm", fixture.request().id(), "manager-1", "An operational reason."));
    }

    @Test
    void requiresAnActionableClosureReasonWithoutMutatingTheFailedRequest() {
        Fixture fixture = fixture(ResourceType.REALM_ROLE, ProvisioningOutcome.FAILED);
        fixture.service().approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved.");
        assertThrows(IllegalArgumentException.class, () -> fixture.service().closeFailedProvisioning(
                fixture.request().realmId(), fixture.request().id(), "manager-1", "too short"));
        assertEquals(ProvisioningStatus.FAILED, fixture.persistedRequest().provisioningStatus());
        assertTrue(!fixture.persistedRequest().provisioningFailureClosed());
        assertEquals("PROVISIONING_FAILED", fixture.eventTypes().getLast());
    }

    @Test
    void revalidatesTheEntitlementUnderTheProvisioningTransactionLock() {
        Fixture fixture = fixture(ResourceType.REALM_ROLE, ProvisioningOutcome.SUCCEEDED);
        Entitlement unpublished = fixture.entitlement().unpublish(CLOCK.instant());
        boolean[] transactionActive = {false};
        EntitlementRepository entitlementRepository = new EntitlementRepository() {
            @Override
            public Optional<Entitlement> findById(String realmId, String entitlementId) {
                throw new AssertionError("Approval must not validate the entitlement before its transaction starts.");
            }

            @Override
            public Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId) {
                assertTrue(transactionActive[0], "The entitlement must be locked in the provisioning transaction.");
                assertEquals(fixture.entitlement().realmId(), realmId);
                assertEquals(fixture.entitlement().id(), entitlementId);
                return Optional.of(unpublished);
            }

            @Override
            public CatalogPage findRequestable(CatalogQuery query) {
                throw new UnsupportedOperationException("Catalog reads are not used by this test double.");
            }
        };
        RequestService service = new RequestService(
                entitlementRepository,
                fixture.requests(),
                (realmId, requesterId, currentEntitlement) -> false,
                (realmId, userId) -> true,
                new RequestPolicy(10, 2_000),
                fixture.events(),
                (realmId, actorId, entitlementId) -> true,
                new AccessRequestTransaction() {
                    @Override
                    public <T> T execute(Supplier<T> operation) {
                        transactionActive[0] = true;
                        try {
                            return operation.get();
                        } finally {
                            transactionActive[0] = false;
                        }
                    }
                },
                List.of(fixture.provisioner()),
                CLOCK);

        assertThrows(EntitlementNotRequestableException.class, () -> service.approve(
                fixture.request().realmId(), fixture.request().id(), "approver-1", "Approved."));
        assertEquals(DecisionStatus.PENDING, fixture.persistedRequest().decisionStatus());
        assertEquals(0, fixture.provisioner().grantAttempts());
        assertEquals(List.of(), fixture.eventTypes());
    }

    private static Fixture fixture(ResourceType resourceType, ProvisioningOutcome outcome) {
        return fixture(resourceType, List.of(outcome));
    }

    private static List<LogRecord> captureProvisioningLogs(Runnable operation) {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(RequestService.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        boolean useParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            operation.run();
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(useParentHandlers);
        }
        return records;
    }

    private static Fixture fixture(ResourceType resourceType, List<ProvisioningOutcome> outcomes) {
        Entitlement entitlement = Entitlement.create(
                        "entitlement-1",
                        "realm-1",
                        resourceType,
                        "resource-1",
                        "Finance Reader",
                        "Access to the Finance Portal.",
                        RiskLevel.HIGH,
                        "finance-approver",
                        Instant.parse("2026-09-01T10:00:00Z"))
                .publish(Instant.parse("2026-09-01T10:00:01Z"));
        AccessRequest request = AccessRequest.create(
                "request-1",
                entitlement.realmId(),
                "requester-1",
                entitlement.id(),
                entitlement.resourceType(),
                entitlement.resourceId(),
                entitlement.displayName(),
                "I need access to prepare the monthly finance report.",
                Instant.parse("2026-09-01T10:05:00Z"));
        InMemoryAccessRequestRepository requests = new InMemoryAccessRequestRepository(request);
        RecordingEventPublisher events = new RecordingEventPublisher();
        RecordingProvisioner provisioner = new RecordingProvisioner(outcomes);
        RequestService service = provisioningEnabledService(entitlement, requests, events, provisioner);
        return new Fixture(service, entitlement, request, requests, events, provisioner);
    }

    private static RequestService provisioningEnabledService(
            Entitlement entitlement,
            InMemoryAccessRequestRepository requests,
            RecordingEventPublisher events,
            RecordingProvisioner provisioner) {
        return provisioningEnabledService(
                new SingleEntitlementRepository(entitlement), requests, events, provisioner);
    }

    private static RequestService provisioningEnabledService(
            EntitlementRepository entitlementRepository,
            InMemoryAccessRequestRepository requests,
            RecordingEventPublisher events,
            RecordingProvisioner provisioner) {
        return new RequestService(
                entitlementRepository,
                requests,
                (EffectiveAccessChecker) (realmId, requesterId, currentEntitlement) -> false,
                (UserStatusReader) (realmId, userId) -> true,
                new RequestPolicy(10, 2_000),
                events,
                (ApprovalAuthorizer) (realmId, actorId, entitlementId) -> true,
                new AccessRequestTransaction() {
                    @Override
                    public <T> T execute(Supplier<T> operation) {
                        return operation.get();
                    }
                },
                List.of(provisioner),
                CLOCK);
    }

    private record Fixture(
            RequestService service,
            Entitlement entitlement,
            AccessRequest request,
            InMemoryAccessRequestRepository requests,
            RecordingEventPublisher events,
            RecordingProvisioner provisioner) {

        private List<String> eventTypes() {
            return events.published().stream().map(event -> event.type().name()).toList();
        }

        private AccessRequest persistedRequest() {
            return requests.findById(request.realmId(), request.id()).orElseThrow();
        }
    }

    private enum ProvisioningOutcome {
        SUCCEEDED,
        FAILED,
        THROWS
    }

    private static final class RecordingProvisioner implements EntitlementProvisioner {

        private final List<ProvisioningOutcome> outcomes;
        private int grantAttempts;
        private String realmId;
        private String requesterId;
        private Entitlement entitlement;

        private RecordingProvisioner(List<ProvisioningOutcome> outcomes) {
            this.outcomes = List.copyOf(outcomes);
        }

        @Override
        public boolean supports(ResourceType resourceType) {
            return true;
        }

        @Override
        public ProvisioningResult grant(String realmId, String requesterId, Entitlement entitlement) {
            grantAttempts++;
            this.realmId = realmId;
            this.requesterId = requesterId;
            this.entitlement = entitlement;
            ProvisioningOutcome outcome = outcomes.get(Math.min(grantAttempts - 1, outcomes.size() - 1));
            return switch (outcome) {
                case SUCCEEDED -> ProvisioningResult.succeeded();
                case FAILED -> ProvisioningResult.failed(ProvisioningFailureCode.RESOURCE_MISSING,
                        "The target resource could not be resolved.");
                case THROWS -> throw new IllegalStateException("Sensitive provider diagnostic");
            };
        }

        int grantAttempts() {
            return grantAttempts;
        }

        String realmId() {
            return realmId;
        }

        String requesterId() {
            return requesterId;
        }

        Entitlement entitlement() {
            return entitlement;
        }
    }

    private static final class SingleEntitlementRepository implements EntitlementRepository {

        private final Entitlement entitlement;

        private SingleEntitlementRepository(Entitlement entitlement) {
            this.entitlement = entitlement;
        }

        @Override
        public Optional<Entitlement> findById(String realmId, String entitlementId) {
            if (entitlement.realmId().equals(realmId) && entitlement.id().equals(entitlementId)) {
                return Optional.of(entitlement);
            }
            return Optional.empty();
        }

        @Override
        public Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId) {
            return findById(realmId, entitlementId);
        }

        @Override
        public CatalogPage findRequestable(CatalogQuery query) {
            throw new UnsupportedOperationException("Catalog reads are not used by this test double.");
        }
    }

    private static final class InMemoryAccessRequestRepository implements AccessRequestRepository {

        private AccessRequest request;

        private InMemoryAccessRequestRepository(AccessRequest request) {
            this.request = request.copy();
        }

        @Override
        public Optional<AccessRequest> findById(String realmId, String requestId) {
            if (request.realmId().equals(realmId) && request.id().equals(requestId)) {
                return Optional.of(request.copy());
            }
            return Optional.empty();
        }

        @Override
        public Optional<AccessRequest> findByIdForUpdate(String realmId, String requestId) {
            return findById(realmId, requestId);
        }

        @Override
        public Optional<AccessRequest> createIfNoPending(AccessRequest newRequest) {
            throw new UnsupportedOperationException("Request creation is not used by this test double.");
        }

        @Override
        public Optional<AccessRequest> updateIfVersionMatches(AccessRequest candidate, long expectedVersion) {
            if (request.version() != expectedVersion) {
                return Optional.empty();
            }
            request = candidate.withVersion(expectedVersion + 1);
            return Optional.of(request.copy());
        }

        @Override
        public AccessRequestPage findByRequester(AccessRequestQuery query) {
            throw new UnsupportedOperationException("Requester reads are not used by this test double.");
        }

        @Override
        public ApprovalQueuePage findPendingForApprover(ApprovalQueueQuery query) {
            throw new UnsupportedOperationException("Approval queue reads are not used by this test double.");
        }

        @Override
        public Set<String> findPendingEntitlementIds(
                String realmId,
                String requesterId,
                Set<String> entitlementIds) {
            throw new UnsupportedOperationException("Catalog state is not used by this test double.");
        }
    }

    private static final class RecordingEventPublisher implements AccessRequestEventPublisher {

        private final List<AccessRequestEvent> events = new ArrayList<>();

        @Override
        public void publish(AccessRequestEvent event) {
            events.add(event);
        }

        List<AccessRequestEvent> published() {
            return List.copyOf(events);
        }
    }
}
