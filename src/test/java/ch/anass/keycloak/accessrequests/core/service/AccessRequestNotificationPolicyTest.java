package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.AccessRequest;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestEvent;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotification;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationRecipientType;
import ch.anass.keycloak.accessrequests.core.domain.AccessRequestNotificationType;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessRequestNotificationPolicyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-22T10:00:00Z");

    private final AccessRequestNotificationPolicy policy = new AccessRequestNotificationPolicy();

    @Test
    void routesNewRequestsToTheEntitlementsApproverRole() {
        AccessRequest request = request();
        Entitlement entitlement = entitlement();

        List<AccessRequestNotification> notifications = policy.notificationsFor(
                request,
                entitlement,
                AccessRequestEvent.created(request, request.requesterId(), CREATED_AT));

        AccessRequestNotification notification = assertSingle(notifications);
        assertAll(
                () -> assertEquals(AccessRequestNotificationType.REQUEST_SUBMITTED, notification.type()),
                () -> assertEquals(AccessRequestNotificationRecipientType.REALM_ROLE, notification.recipientType()),
                () -> assertEquals(entitlement.approverRoleId(), notification.recipientId()),
                () -> assertEquals(request.id(), notification.request().id()),
                () -> assertEquals(request.justification(), notification.request().justification()),
                () -> assertEquals(entitlement.id(), notification.entitlement().id()),
                () -> assertEquals(entitlement.displayName(), notification.entitlement().displayName()));
    }

    @Test
    void routesApprovalAndRejectionToTheRequester() {
        AccessRequest request = request();
        Entitlement entitlement = entitlement();

        AccessRequestNotification approval = assertSingle(policy.notificationsFor(
                request,
                entitlement,
                AccessRequestEvent.approved(request, "approver-1", CREATED_AT, "Approved for the close.")));
        AccessRequestNotification rejection = assertSingle(policy.notificationsFor(
                request,
                entitlement,
                AccessRequestEvent.rejected(request, "approver-1", CREATED_AT, "Please add more context.")));

        assertAll(
                () -> assertEquals(AccessRequestNotificationType.REQUEST_APPROVED, approval.type()),
                () -> assertEquals(AccessRequestNotificationType.REQUEST_REJECTED, rejection.type()),
                () -> assertEquals(AccessRequestNotificationRecipientType.USER, approval.recipientType()),
                () -> assertEquals(AccessRequestNotificationRecipientType.USER, rejection.recipientType()),
                () -> assertEquals(request.requesterId(), approval.recipientId()),
                () -> assertEquals(request.requesterId(), rejection.recipientId()),
                () -> assertEquals("Approved for the close.", approval.event().comment()),
                () -> assertEquals("Please add more context.", rejection.event().comment()));
    }

    @Test
    void routesProvisioningFailuresToTheRequester() {
        AccessRequest request = request();

        AccessRequestNotification notification = assertSingle(policy.notificationsFor(
                request,
                entitlement(),
                AccessRequestEvent.provisioningFailed(
                        request,
                        "approver-1",
                        CREATED_AT,
                        "The configured role no longer exists.")));

        assertAll(
                () -> assertEquals(AccessRequestNotificationType.PROVISIONING_FAILED, notification.type()),
                () -> assertEquals(AccessRequestNotificationRecipientType.USER, notification.recipientType()),
                () -> assertEquals(request.requesterId(), notification.recipientId()),
                () -> assertEquals("The configured role no longer exists.", notification.event().comment()));
    }

    @Test
    void doesNotSendDuplicateNotificationsForTechnicalOrCancellationEvents() {
        AccessRequest request = request();
        Entitlement entitlement = entitlement();

        assertEquals(List.of(), policy.notificationsFor(
                request,
                entitlement,
                AccessRequestEvent.canceled(request, request.requesterId(), CREATED_AT)));
        assertEquals(List.of(), policy.notificationsFor(
                request,
                entitlement,
                AccessRequestEvent.provisioningStarted(request, "approver-1", CREATED_AT)));
        assertEquals(List.of(), policy.notificationsFor(
                request,
                entitlement,
                AccessRequestEvent.provisioningSucceeded(request, "approver-1", CREATED_AT)));
    }

    @Test
    void rejectsEventsThatDoNotBelongToTheSuppliedRequestAndEntitlement() {
        AccessRequest request = request();
        Entitlement entitlement = entitlement();
        AccessRequest anotherRequest = AccessRequest.create(
                "request-2",
                request.realmId(),
                request.requesterId(),
                entitlement.id(),
                entitlement.resourceType(),
                entitlement.resourceId(),
                entitlement.displayName(),
                "Access is needed for the finance close.",
                CREATED_AT);
        Entitlement anotherRealmEntitlement = Entitlement.create(
                entitlement.id(),
                "realm-2",
                entitlement.resourceType(),
                entitlement.resourceId(),
                entitlement.displayName(),
                entitlement.description(),
                entitlement.riskLevel(),
                entitlement.approverRoleId(),
                CREATED_AT);

        assertThrows(IllegalArgumentException.class, () -> policy.notificationsFor(
                request,
                entitlement,
                AccessRequestEvent.created(anotherRequest, anotherRequest.requesterId(), CREATED_AT)));
        assertThrows(IllegalArgumentException.class, () -> policy.notificationsFor(
                request,
                anotherRealmEntitlement,
                AccessRequestEvent.created(request, request.requesterId(), CREATED_AT)));
    }

    private static AccessRequestNotification assertSingle(List<AccessRequestNotification> notifications) {
        assertEquals(1, notifications.size());
        return notifications.getFirst();
    }

    private static AccessRequest request() {
        return AccessRequest.create(
                "request-1",
                "realm-1",
                "requester-1",
                "entitlement-1",
                ResourceType.REALM_ROLE,
                "finance-reader",
                "Finance Reader",
                "Access is needed for the finance close.",
                CREATED_AT);
    }

    private static Entitlement entitlement() {
        return Entitlement.create(
                "entitlement-1",
                "realm-1",
                ResourceType.REALM_ROLE,
                "finance-reader",
                "Finance Reader",
                "Read access to finance reporting.",
                RiskLevel.MEDIUM,
                "finance-approver",
                CREATED_AT);
    }
}
