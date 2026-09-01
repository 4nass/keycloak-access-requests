package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.ApprovalAuthorizer;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementScopedApprovalAuthorizerTest {

    private final InMemoryEntitlementRepository entitlements = new InMemoryEntitlementRepository();
    private final InMemoryRoleMemberships roleMemberships = new InMemoryRoleMemberships();

    @Test
    void authorizesAnActorWithTheEntitlementsConfiguredApproverRole() {
        entitlements.add(entitlement("realm-1", "entitlement-finance", "finance-approver"));
        roleMemberships.grant("realm-1", "approver-1", "finance-approver");

        assertTrue(authorizer().canDecide("realm-1", "approver-1", "entitlement-finance"));
    }

    @Test
    void doesNotAuthorizeAnActorWhoseRoleIsConfiguredForAnotherEntitlement() {
        entitlements.add(entitlement("realm-1", "entitlement-finance", "finance-approver"));
        entitlements.add(entitlement("realm-1", "entitlement-hr", "hr-approver"));
        roleMemberships.grant("realm-1", "approver-1", "finance-approver");

        assertFalse(authorizer().canDecide("realm-1", "approver-1", "entitlement-hr"));
    }

    @Test
    void doesNotUseRoleMembershipOrEntitlementsFromAnotherRealm() {
        entitlements.add(entitlement("realm-2", "entitlement-finance", "finance-approver"));
        roleMemberships.grant("realm-2", "approver-1", "finance-approver");

        assertFalse(authorizer().canDecide("realm-1", "approver-1", "entitlement-finance"));
    }

    @Test
    void doesNotAuthorizeUnknownEntitlements() {
        roleMemberships.grant("realm-1", "approver-1", "finance-approver");

        assertFalse(authorizer().canDecide("realm-1", "approver-1", "unknown-entitlement"));
    }

    private ApprovalAuthorizer authorizer() {
        try {
            Class<?> roleMembershipReader = Class.forName(
                    "ch.anass.keycloak.accessrequests.core.port.RoleMembershipReader");
            Object membershipReader = Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{roleMembershipReader},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("hasRole")) {
                            return roleMemberships.hasRole(
                                    (String) arguments[0], (String) arguments[1], (String) arguments[2]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            Class<?> authorizerType = Class.forName(
                    "ch.anass.keycloak.accessrequests.core.service.EntitlementScopedApprovalAuthorizer");
            return (ApprovalAuthorizer) authorizerType
                    .getDeclaredConstructor(EntitlementRepository.class, roleMembershipReader)
                    .newInstance(entitlements, membershipReader);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "The core must provide an entitlement-scoped approval authorizer and role membership port.",
                    exception);
        }
    }

    private static Entitlement entitlement(String realmId, String entitlementId, String approverRoleId) {
        return Entitlement.create(
                entitlementId,
                realmId,
                ResourceType.CLIENT_ROLE,
                "role-" + entitlementId,
                "Finance Reader",
                "Read-only access to the Finance Portal.",
                RiskLevel.LOW,
                approverRoleId,
                Instant.EPOCH).publish(Instant.EPOCH);
    }

    private static final class InMemoryEntitlementRepository implements EntitlementRepository {

        private final Map<String, Entitlement> values = new HashMap<>();

        void add(Entitlement entitlement) {
            values.put(key(entitlement.realmId(), entitlement.id()), entitlement);
        }

        @Override
        public Optional<Entitlement> findById(String realmId, String entitlementId) {
            return Optional.ofNullable(values.get(key(realmId, entitlementId)));
        }

        @Override
        public Optional<Entitlement> findByIdForUpdate(String realmId, String entitlementId) {
            return findById(realmId, entitlementId);
        }

        @Override
        public CatalogPage findRequestable(CatalogQuery query) {
            throw new UnsupportedOperationException("Catalog reads are not used by this test double.");
        }

        private static String key(String realmId, String entitlementId) {
            return realmId + ":" + entitlementId;
        }
    }

    private static final class InMemoryRoleMemberships {

        private final Set<String> values = new HashSet<>();

        void grant(String realmId, String actorId, String roleId) {
            values.add(key(realmId, actorId, roleId));
        }

        boolean hasRole(String realmId, String actorId, String roleId) {
            return values.contains(key(realmId, actorId, roleId));
        }

        private static String key(String realmId, String actorId, String roleId) {
            return realmId + ":" + actorId + ":" + roleId;
        }
    }
}
