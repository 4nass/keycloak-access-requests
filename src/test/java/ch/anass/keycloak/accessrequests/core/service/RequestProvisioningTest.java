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
import ch.anass.keycloak.accessrequests.core.domain.InvalidRequestStateException;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningResult;
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
        RecordingProvisioner provisioner = new RecordingProvisioner(outcome);
        RequestService service = provisioningEnabledService(entitlement, requests, events, provisioner);
        return new Fixture(service, entitlement, request, requests, events, provisioner);
    }

    private static RequestService provisioningEnabledService(
            Entitlement entitlement,
            InMemoryAccessRequestRepository requests,
            RecordingEventPublisher events,
            RecordingProvisioner provisioner) {
        return new RequestService(
                new SingleEntitlementRepository(entitlement),
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
        FAILED
    }

    private static final class RecordingProvisioner implements EntitlementProvisioner {

        private final ProvisioningOutcome outcome;
        private int grantAttempts;
        private String realmId;
        private String requesterId;
        private Entitlement entitlement;

        private RecordingProvisioner(ProvisioningOutcome outcome) {
            this.outcome = outcome;
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
            return switch (outcome) {
                case SUCCEEDED -> ProvisioningResult.succeeded();
                case FAILED -> ProvisioningResult.failed("The target resource could not be resolved.");
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
