package ch.anass.keycloak.accessrequests.spi.provisioning;

import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningResult;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningFailureCode;
import ch.anass.keycloak.accessrequests.core.domain.ProvisioningStatus;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.EntitlementProvisioner;
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

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakEntitlementProvisionerTest {

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void grantsEveryV0EntitlementTypeToTheRequester(ResourceType resourceType) {
        KeycloakFixture fixture = KeycloakFixture.withTarget(resourceType);

        ProvisioningResult result = grant(provisioner(fixture), fixture.entitlement());

        assertTrue(provisioner(fixture).supports(resourceType));
        assertEquals(ProvisioningStatus.SUCCEEDED, result.status());
        fixture.assertSingleGrant(resourceType);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void treatsAnAlreadyGrantedEntitlementAsASuccessfulNoOp(ResourceType resourceType) {
        KeycloakFixture fixture = KeycloakFixture.withTarget(resourceType);
        EntitlementProvisioner provisioner = provisioner(fixture);

        assertEquals(ProvisioningStatus.SUCCEEDED, grant(provisioner, fixture.entitlement()).status());
        assertEquals(ProvisioningStatus.SUCCEEDED, grant(provisioner, fixture.entitlement()).status());

        fixture.assertSingleGrant(resourceType);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void recordsFailureWhenTheConfiguredKeycloakResourceNoLongerExists(ResourceType resourceType) {
        KeycloakFixture fixture = KeycloakFixture.withoutTarget(resourceType);

        ProvisioningResult result = grant(provisioner(fixture), fixture.entitlement());

        assertEquals(ProvisioningStatus.FAILED, result.status());
        assertEquals(ProvisioningFailureCode.RESOURCE_MISSING, result.failureCode());
        fixture.assertNoGrant();
    }

    @Test
    void recordsFailureWhenTheRequesterNoLongerExists() {
        KeycloakFixture fixture = KeycloakFixture.withTarget(ResourceType.REALM_ROLE);
        fixture.removeRequester();

        ProvisioningResult result = grant(provisioner(fixture), fixture.entitlement());

        assertEquals(ProvisioningStatus.FAILED, result.status());
        assertEquals(ProvisioningFailureCode.REQUESTER_MISSING, result.failureCode());
        fixture.assertNoGrant();
    }

    @Test
    void logsUnexpectedKeycloakFailureWithoutReturningItsSensitiveMessage() {
        KeycloakFixture fixture = KeycloakFixture.withTarget(ResourceType.REALM_ROLE);
        RuntimeException failure = new IllegalStateException("Sensitive Keycloak diagnostic");
        fixture.failRequesterLookup(failure);
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(KeycloakEntitlementProvisioner.class.getName());
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
        ProvisioningResult result;
        try {
            result = grant(provisioner(fixture), fixture.entitlement());
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(useParentHandlers);
        }

        assertEquals(ProvisioningFailureCode.UNEXPECTED_FAILURE, result.failureCode());
        assertFalse(result.failureReason().contains("Sensitive Keycloak diagnostic"));
        assertEquals(1, records.size());
        assertEquals(Level.SEVERE, records.getFirst().getLevel());
        assertEquals(failure, records.getFirst().getThrown());
        assertTrue(records.getFirst().getMessage().contains("realmId=realm-1"));
        assertTrue(records.getFirst().getMessage().contains("requesterId=requester-1"));
        assertTrue(records.getFirst().getMessage().contains("entitlementId=entitlement-1"));
    }

    @Test
    void refusesToProvisionAcrossRealms() {
        KeycloakFixture fixture = KeycloakFixture.withTarget(ResourceType.CLIENT_ROLE);
        EntitlementProvisioner provisioner = provisioner(fixture);

        ProvisioningResult result = grant(provisioner, "another-realm", fixture.requesterId(), fixture.entitlement());

        assertEquals(ProvisioningStatus.FAILED, result.status());
        assertEquals(ProvisioningFailureCode.REALM_MISMATCH, result.failureCode());
        fixture.assertNoGrant();
    }

    @Test
    void refusesAClientRoleConfiguredAsARealmRole() {
        KeycloakFixture fixture = KeycloakFixture.withTarget(ResourceType.CLIENT_ROLE);

        ProvisioningResult result = grant(provisioner(fixture), fixture.entitlement(ResourceType.REALM_ROLE));

        assertEquals(ProvisioningStatus.FAILED, result.status());
        assertEquals(ProvisioningFailureCode.RESOURCE_TYPE_MISMATCH, result.failureCode());
        fixture.assertNoGrant();
    }

    @Test
    void refusesARealmRoleConfiguredAsAClientRole() {
        KeycloakFixture fixture = KeycloakFixture.withTarget(ResourceType.REALM_ROLE);

        ProvisioningResult result = grant(provisioner(fixture), fixture.entitlement(ResourceType.CLIENT_ROLE));

        assertEquals(ProvisioningStatus.FAILED, result.status());
        assertEquals(ProvisioningFailureCode.RESOURCE_TYPE_MISMATCH, result.failureCode());
        fixture.assertNoGrant();
    }

    private static EntitlementProvisioner provisioner(KeycloakFixture fixture) {
        return new KeycloakEntitlementProvisioner(fixture.session(), fixture.realm());
    }

    private static ProvisioningResult grant(EntitlementProvisioner provisioner, Entitlement entitlement) {
        return grant(provisioner, entitlement.realmId(), "requester-1", entitlement);
    }

    private static ProvisioningResult grant(
            EntitlementProvisioner provisioner,
            String realmId,
            String requesterId,
            Entitlement entitlement) {
        return provisioner.grant(realmId, requesterId, entitlement);
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
        private RuntimeException requesterLookupFailure;
        private int roleGrantCalls;
        private int groupJoinCalls;

        private KeycloakFixture(ResourceType resourceType, boolean targetExists) {
            this.resourceType = resourceType;
            if (targetExists) {
                switch (resourceType) {
                    case REALM_ROLE -> roles.put(RESOURCE_ID, role(RESOURCE_ID, false));
                    case CLIENT_ROLE -> roles.put(RESOURCE_ID, role(RESOURCE_ID, true));
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
            return entitlement(resourceType);
        }

        Entitlement entitlement(ResourceType configuredResourceType) {
            return Entitlement.create(
                            "entitlement-1",
                            REALM_ID,
                            configuredResourceType,
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

        void failRequesterLookup(RuntimeException failure) {
            requesterLookupFailure = failure;
        }

        RealmModel realm() {
            return proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "getId" -> REALM_ID;
                case "getRoleById" -> roles.get(arguments[0]);
                default -> null;
            });
        }

        KeycloakSession session() {
            UserProvider users = proxy(UserProvider.class, (proxy, method, arguments) -> {
                if (!method.getName().equals("getUserById")) {
                    return null;
                }
                if (requesterLookupFailure != null) {
                    throw requesterLookupFailure;
                }
                return REQUESTER_ID.equals(arguments[1]) && requesterExists ? requester() : null;
            });
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

        private static RoleModel role(String id, boolean clientRole) {
            return proxy(RoleModel.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "getId" -> id;
                case "isClientRole" -> clientRole;
                default -> null;
            });
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
