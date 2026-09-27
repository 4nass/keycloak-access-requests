package ch.anass.keycloak.accessrequests.spi.realm;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.Entitlement;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.AdminEventBuilder;

import java.util.Objects;

/**
 * Mirrors catalog mutations into the realm's configured Keycloak Admin Event store.
 * The extension's entitlement history remains the durable source of truth.
 */
public final class KeycloakEntitlementAdminEventPublisher {

    private static final String RESOURCE_TYPE = "ACCESS_REQUEST_ENTITLEMENT";
    private final KeycloakSession session;
    private final RealmModel realm;
    private final AdminAuth auth;

    public KeycloakEntitlementAdminEventPublisher(KeycloakSession session, RealmModel realm, AdminAuth auth) {
        this.session = Objects.requireNonNull(session);
        this.realm = Objects.requireNonNull(realm);
        this.auth = Objects.requireNonNull(auth);
    }

    public void created(Entitlement entitlement) {
        publish(entitlement, OperationType.CREATE);
    }

    public void updated(Entitlement entitlement) {
        publish(entitlement, OperationType.UPDATE);
    }

    private void publish(Entitlement entitlement, OperationType operation) {
        new AdminEventBuilder(realm, auth, session, session.getContext().getConnection())
                .resource(RESOURCE_TYPE)
                .resourcePath("access-requests", "entitlements", entitlement.id())
                .operation(operation)
                .detail("requestable", Boolean.toString(entitlement.requestable()))
                .detail("riskLevel", entitlement.riskLevel().name())
                .detail("approverRoleId", entitlement.approverRoleId())
                .detail("defaultDurationSeconds", Long.toString(entitlement.durationPolicy().defaultDuration().toSeconds()))
                .detail("maxDurationSeconds", Long.toString(entitlement.durationPolicy().maxDuration().toSeconds()))
                .detail("allowPermanent", Boolean.toString(entitlement.durationPolicy().allowPermanent()))
                .success();
    }
}
