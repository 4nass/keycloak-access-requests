package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.RoleProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class KeycloakReferenceSearchTest {

    @Test
    void neverEnumeratesTheRealmForAnEmptyOrOneCharacterSearch() {
        RealmModel realm = rejecting(RealmModel.class);

        assertEquals(List.of(), KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class),
                rejecting(GroupProvider.class), ResourceType.REALM_ROLE, "", null, 0, 50).items());
        assertEquals(List.of(), KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class),
                rejecting(GroupProvider.class), ResourceType.GROUP, "a", null, 0, 50).items());
    }

    @Test
    void searchesRealmRolesAtTheSourceAndConsumesNoMoreThanMax() {
        RoleModel first = role("role-1", "Finance Reader", false, null);
        RoleModel second = role("role-2", "Finance Writer", false, null);
        RoleModel third = role("role-3", "Finance Auditor", false, null);
        AtomicInteger searched = new AtomicInteger();
        AtomicInteger visited = new AtomicInteger();
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getRoleById" -> null;
            case "searchForRolesStream" -> {
                assertEquals("finance", arguments[0]);
                assertEquals(0, arguments[1]);
                assertEquals(3, arguments[2]);
                searched.incrementAndGet();
                yield Stream.of(first, second, third).peek(role -> visited.incrementAndGet());
            }
            default -> throw new AssertionError("Unexpected realm-wide call: " + method.getName());
        });

        var matches = KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class),
                rejecting(GroupProvider.class), ResourceType.REALM_ROLE, "finance", null, 0, 2);

        assertEquals(List.of("role-1", "role-2"), matches.items().stream().map(reference -> reference.id()).toList());
        assertEquals(2, matches.nextFirst());
        assertEquals(true, matches.hasMore());
        assertEquals(1, searched.get());
        assertEquals(3, visited.get());
    }

    @Test
    void searchesClientRolesAcrossTheRealmWithoutEnumeratingClients() {
        ClientModel client = proxy(ClientModel.class, (proxy, method, arguments) -> {
            if (method.getName().equals("getClientId")) {
                return "finance-portal";
            }
            throw new AssertionError("Unexpected client call: " + method.getName());
        });
        RoleModel role = role("client-role-1", "Reader", true, client);
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> {
            if (method.getName().equals("getRoleById")) {
                return null;
            }
            throw new AssertionError("Unexpected realm-wide call: " + method.getName());
        });
        RoleProvider roles = proxy(RoleProvider.class, (proxy, method, arguments) -> {
            assertEquals("searchForClientRolesStream", method.getName());
            assertSame(realm, arguments[0]);
            assertEquals("finance", arguments[1]);
            assertEquals(0, arguments[3]);
            assertEquals(11, arguments[4]);
            return Stream.of(role);
        });

        var matches = KeycloakReferenceSearch.find(realm, roles, rejecting(GroupProvider.class),
                ResourceType.CLIENT_ROLE, "finance", null, 0, 10);

        assertEquals(List.of("finance-portal / Reader"), matches.items().stream().map(reference -> reference.name()).toList());
        assertEquals("finance-portal / Reader", KeycloakReferenceSearch.roleDisplayName(ResourceType.CLIENT_ROLE, role));
    }

    @Test
    void searchesGroupsAtTheSourceAndResolvesTheSelectedIdWithoutANameSearch() {
        GroupModel parent = group("group-parent", "Finance", null);
        GroupModel child = group("group-child", "Readers", parent);
        RealmModel realm = rejecting(RealmModel.class);
        GroupProvider groups = proxy(GroupProvider.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getGroupById" -> {
                assertSame(realm, arguments[0]);
                yield "group-child".equals(arguments[1]) ? child : null;
            }
            case "searchForGroupByNameStream" -> {
                assertSame(realm, arguments[0]);
                assertEquals("read", arguments[1]);
                assertEquals(false, arguments[2]);
                assertEquals(0, arguments[3]);
                assertEquals(6, arguments[4]);
                yield Stream.of(child);
            }
            default -> throw new AssertionError("Unexpected group-wide call: " + method.getName());
        });

        var selected = KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class), groups,
                ResourceType.GROUP, "", "group-child", 0, 5);
        var searched = KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class), groups,
                ResourceType.GROUP, "read", "group-child", 0, 5);

        assertEquals(List.of("/Finance/Readers"), selected.items().stream().map(reference -> reference.name()).toList());
        assertEquals(List.of("group-child"), searched.items().stream().map(reference -> reference.id()).toList());
    }

    @Test
    void resolvesAnExactRoleIdButRejectsTheWrongResourceType() {
        RoleModel role = role("realm-role-1", "Reader", false, null);
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> {
            if (method.getName().equals("getRoleById")) {
                return "realm-role-1".equals(arguments[0]) ? role : null;
            }
            throw new AssertionError("Unexpected realm-wide call: " + method.getName());
        });

        assertEquals(List.of("realm-role-1"), KeycloakReferenceSearch.find(realm,
                rejecting(RoleProvider.class), rejecting(GroupProvider.class), ResourceType.REALM_ROLE,
                "", "realm-role-1", 0, 10).items().stream().map(reference -> reference.id()).toList());
        assertEquals(List.of(), KeycloakReferenceSearch.find(realm,
                rejecting(RoleProvider.class), rejecting(GroupProvider.class), ResourceType.CLIENT_ROLE,
                "", "realm-role-1", 0, 10).items());
    }

    @Test
    void pagesThroughSearchResultsWithoutMixingInTheSelectedRole() {
        List<RoleModel> roles = List.of(
                role("role-1", "Role 1", false, null), role("role-2", "Role 2", false, null),
                role("role-3", "Role 3", false, null), role("role-4", "Role 4", false, null));
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getRoleById" -> roles.stream().filter(role -> role.getId().equals(arguments[0])).findFirst().orElse(null);
            case "searchForRolesStream" -> roles.stream().skip((int) arguments[1]).limit((int) arguments[2]);
            default -> throw new AssertionError("Unexpected realm-wide call: " + method.getName());
        });

        var first = KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class), rejecting(GroupProvider.class),
                ResourceType.REALM_ROLE, "role", "role-1", 0, 2);
        var second = KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class), rejecting(GroupProvider.class),
                ResourceType.REALM_ROLE, "role", "role-1", first.nextFirst(), 2);

        assertEquals(List.of("role-1", "role-2"), first.items().stream().map(reference -> reference.id()).toList());
        assertEquals(2, first.nextFirst());
        assertEquals(true, first.hasMore());
        assertEquals(List.of("role-3", "role-4"), second.items().stream().map(reference -> reference.id()).toList());
        assertEquals(4, second.nextFirst());
        assertEquals(false, second.hasMore());
    }

    @Test
    void advancesWithMaxOneEvenWhenAnExistingSelectionIsProvided() {
        List<RoleModel> roles = List.of(role("role-1", "Role 1", false, null),
                role("role-2", "Role 2", false, null));
        RealmModel realm = proxy(RealmModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getRoleById" -> roles.stream().filter(role -> role.getId().equals(arguments[0])).findFirst().orElse(null);
            case "searchForRolesStream" -> roles.stream().skip((int) arguments[1]).limit((int) arguments[2]);
            default -> throw new AssertionError("Unexpected realm-wide call: " + method.getName());
        });

        var first = KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class), rejecting(GroupProvider.class),
                ResourceType.REALM_ROLE, "role", "role-2", 0, 1);
        var second = KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class), rejecting(GroupProvider.class),
                ResourceType.REALM_ROLE, "role", "role-2", first.nextFirst(), 1);
        var exact = KeycloakReferenceSearch.find(realm, rejecting(RoleProvider.class), rejecting(GroupProvider.class),
                ResourceType.REALM_ROLE, "role-2", null, 0, 1);

        assertEquals(List.of("role-1"), first.items().stream().map(reference -> reference.id()).toList());
        assertEquals(1, first.nextFirst());
        assertEquals(true, first.hasMore());
        assertEquals(List.of("role-2"), second.items().stream().map(reference -> reference.id()).toList());
        assertEquals(false, second.hasMore());
        assertEquals(List.of("role-2"), exact.items().stream().map(reference -> reference.id()).toList());
        assertEquals(false, exact.hasMore());
    }

    private static RoleModel role(String id, String name, boolean clientRole, ClientModel client) {
        return proxy(RoleModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> id;
            case "getName" -> name;
            case "getDescription" -> null;
            case "isClientRole" -> clientRole;
            case "getContainer" -> client;
            default -> throw new AssertionError("Unexpected role call: " + method.getName());
        });
    }

    private static GroupModel group(String id, String name, GroupModel parent) {
        return proxy(GroupModel.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getId" -> id;
            case "getName" -> name;
            case "getParent" -> parent;
            default -> throw new AssertionError("Unexpected group call: " + method.getName());
        });
    }

    private static <T> T rejecting(Class<T> type) {
        return proxy(type, (proxy, method, arguments) -> {
            throw new AssertionError("Unexpected " + type.getSimpleName() + " call: " + method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
