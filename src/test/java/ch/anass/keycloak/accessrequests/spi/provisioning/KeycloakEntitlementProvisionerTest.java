package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakEntitlementProvisionerTest {

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void grantsEveryV0EntitlementTypeToTheRequester(ResourceType resourceType) {
        KeycloakFixture fixture = KeycloakFixture.withTarget(resourceType);

        Object result = grant(provisioner(fixture), fixture.entitlement());

        assertTrue(supports(provisioner(fixture), resourceType));
        assertEquals(ProvisioningStatus.SUCCEEDED, status(result));
        fixture.assertSingleGrant(resourceType);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void treatsAnAlreadyGrantedEntitlementAsASuccessfulNoOp(ResourceType resourceType) {
        KeycloakFixture fixture = KeycloakFixture.withTarget(resourceType);
        Object provisioner = provisioner(fixture);

        assertEquals(ProvisioningStatus.SUCCEEDED, status(grant(provisioner, fixture.entitlement())));
        assertEquals(ProvisioningStatus.SUCCEEDED, status(grant(provisioner, fixture.entitlement())));

        fixture.assertSingleGrant(resourceType);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void recordsFailureWhenTheConfiguredKeycloakResourceNoLongerExists(ResourceType resourceType) {
        KeycloakFixture fixture = KeycloakFixture.withoutTarget(resourceType);

        Object result = grant(provisioner(fixture), fixture.entitlement());

        assertEquals(ProvisioningStatus.FAILED, status(result));
        fixture.assertNoGrant();
    }

    @Test
    void recordsFailureWhenTheRequesterNoLongerExists() {
        KeycloakFixture fixture = KeycloakFixture.withTarget(ResourceType.REALM_ROLE);
        fixture.removeRequester();

        Object result = grant(provisioner(fixture), fixture.entitlement());

        assertEquals(ProvisioningStatus.FAILED, status(result));
        fixture.assertNoGrant();
    }

    @Test
    void refusesToProvisionAcrossRealms() {
        KeycloakFixture fixture = KeycloakFixture.withTarget(ResourceType.CLIENT_ROLE);
        Object provisioner = provisioner(fixture);

        Object result = grant(provisioner, "another-realm", fixture.requesterId(), fixture.entitlement());

        assertEquals(ProvisioningStatus.FAILED, status(result));
        fixture.assertNoGrant();
    }

    private static Object provisioner(KeycloakFixture fixture) {
        try {
            Class<?> provisionerType = Class.forName(
                    "ch.anass.keycloak.accessrequests.core.port.EntitlementProvisioner");
            Class<?> adapterType = Class.forName(
                    "ch.anass.keycloak.accessrequests.spi.provisioning.KeycloakEntitlementProvisioner");
            Object provisioner = adapterType
                    .getDeclaredConstructor(KeycloakSession.class, RealmModel.class)
                    .newInstance(fixture.session(), fixture.realm());
            assertTrue(provisionerType.isInstance(provisioner));
            return provisioner;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "The SPI must provide a Keycloak entitlement provisioner implementing the core port.",
                    exception);
        }
    }

    private static boolean supports(Object provisioner, ResourceType resourceType) {
        try {
            return (boolean) provisioner.getClass()
                    .getMethod("supports", ResourceType.class)
                    .invoke(provisioner, resourceType);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("The provisioner must declare the resource types it supports.", exception);
        }
    }

    private static Object grant(Object provisioner, Entitlement entitlement) {
        return grant(provisioner, entitlement.realmId(), "requester-1", entitlement);
    }

    private static Object grant(Object provisioner, String realmId, String requesterId, Entitlement entitlement) {
        try {
            return provisioner.getClass()
                    .getMethod("grant", String.class, String.class, Entitlement.class)
                    .invoke(provisioner, realmId, requesterId, entitlement);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("Provisioning failures must be returned as a result, not escape the port.",
                    exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("The provisioner must grant an entitlement to a requester.", exception);
        }
    }

    private static ProvisioningStatus status(Object result) {
        try {
            return (ProvisioningStatus) result.getClass().getMethod("status").invoke(result);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("A provisioning result must expose its final status.", exception);
        }
    }

    private static final class KeycloakFixture {

        private static final String REALM_ID = "realm-1";
        private static final String REQUESTER_ID = "requester-1";
        private static final String RESOURCE_ID = "resource-1";

        private final ResourceType resourceType;
        private final Map<String, RoleModel> roles = new HashMap<>();
        private final Map<String, GroupModel> groups = new HashMap<>();
        private final Set<RoleModel> grantedRoles = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<GroupModel> joinedGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean requesterExists = true;
        private int roleGrantCalls;
        private int groupJoinCalls;

        private KeycloakFixture(ResourceType resourceType, boolean targetExists) {
            this.resourceType = resourceType;
            if (targetExists) {
                switch (resourceType) {
                    case REALM_ROLE, CLIENT_ROLE -> roles.put(RESOURCE_ID, role(RESOURCE_ID));
                    case GROUP -> groups.put(RESOURCE_ID, group(RESOURCE_ID));
                }
            }
        }

        static KeycloakFixture withTarget(ResourceType resourceType) {
            return new KeycloakFixture(resourceType, true);
        }

        static KeycloakFixture withoutTarget(ResourceType resourceType) {
            return new KeycloakFixture(resourceType, false);
        }

        Entitlement entitlement() {
            return Entitlement.create(
                            "entitlement-1",
                            REALM_ID,
                            resourceType,
                            RESOURCE_ID,
                            "Finance Reader",
                            "Access to the Finance Portal.",
                            RiskLevel.HIGH,
                            "finance-approver",
                            Instant.parse("2026-09-01T10:00:00Z"))
                    .publish(Instant.parse("2026-09-01T10:00:01Z"));
        }

        String requesterId() {
            return REQUESTER_ID;
        }

        void removeRequester() {
            requesterExists = false;
        }

        RealmModel realm() {
            return proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "getId" -> REALM_ID;
                case "getRoleById" -> roles.get(arguments[0]);
                default -> null;
            });
        }

        KeycloakSession session() {
            UserProvider users = proxy(UserProvider.class, (proxy, method, arguments) ->
                    method.getName().equals("getUserById") && REQUESTER_ID.equals(arguments[1]) && requesterExists
                            ? requester()
                            : null);
            GroupProvider groupsProvider = proxy(GroupProvider.class, (proxy, method, arguments) ->
                    method.getName().equals("getGroupById") ? groups.get(arguments[1]) : null);
            return proxy(KeycloakSession.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "users" -> users;
                case "groups" -> groupsProvider;
                default -> null;
            });
        }

        void assertSingleGrant(ResourceType type) {
            switch (type) {
                case REALM_ROLE, CLIENT_ROLE -> {
                    assertEquals(1, roleGrantCalls);
                    assertEquals(0, groupJoinCalls);
                }
                case GROUP -> {
                    assertEquals(0, roleGrantCalls);
                    assertEquals(1, groupJoinCalls);
                }
            }
        }

        void assertNoGrant() {
            assertEquals(0, roleGrantCalls);
            assertEquals(0, groupJoinCalls);
        }

        private UserModel requester() {
            return proxy(UserModel.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "hasRole" -> grantedRoles.contains(arguments[0]);
                case "grantRole" -> {
                    grantedRoles.add((RoleModel) arguments[0]);
                    roleGrantCalls++;
                    yield null;
                }
                case "isMemberOf" -> joinedGroups.contains(arguments[0]);
                case "joinGroup" -> {
                    joinedGroups.add((GroupModel) arguments[0]);
                    groupJoinCalls++;
                    yield null;
                }
                default -> null;
            });
        }

        private static RoleModel role(String id) {
            return proxy(RoleModel.class, (proxy, method, arguments) ->
                    method.getName().equals("getId") ? id : null);
        }

        private static GroupModel group(String id) {
            return proxy(GroupModel.class, (proxy, method, arguments) ->
                    method.getName().equals("getId") ? id : null);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
