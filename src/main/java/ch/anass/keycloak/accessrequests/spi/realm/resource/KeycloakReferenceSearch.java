package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.spi.realm.dto.CatalogDto.KeycloakReferenceResponse;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.RoleProvider;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

final class KeycloakReferenceSearch {

    private KeycloakReferenceSearch() {
    }

    record Page(List<KeycloakReferenceResponse> items, int nextFirst, boolean hasMore) {
    }

    static Page find(RealmModel realm, RoleProvider roles, GroupProvider groups,
            ResourceType type, String search, String selectedId, int first, int max) {
        String term = search == null ? "" : search.trim();
        if (term.length() < 2) {
            KeycloakReferenceResponse selected = first == 0 ? findExact(realm, groups, type, selectedId) : null;
            return new Page(selected == null ? List.of() : List.of(selected), first, false);
        }
        if (first == 0) {
            KeycloakReferenceResponse exact = findExact(realm, groups, type, term);
            if (exact != null) {
                return new Page(List.of(exact), 0, false);
            }
        }

        List<KeycloakReferenceResponse> matches = new ArrayList<>(max);
        try (Stream<KeycloakReferenceResponse> found = search(realm, roles, groups, type, term, first, max + 1)) {
            Iterator<KeycloakReferenceResponse> iterator = found.iterator();
            while (iterator.hasNext()) {
                KeycloakReferenceResponse reference = iterator.next();
                if (matches.size() == max) {
                    return new Page(List.copyOf(matches), first + matches.size(), true);
                }
                matches.add(reference);
            }
        }
        return new Page(List.copyOf(matches), first + matches.size(), false);
    }

    private static KeycloakReferenceResponse findExact(RealmModel realm,
            GroupProvider groups, ResourceType type, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return switch (type) {
            case REALM_ROLE, CLIENT_ROLE -> {
                RoleModel role = realm.getRoleById(id);
                if (role != null && role.isClientRole() == (type == ResourceType.CLIENT_ROLE)) {
                    yield roleReference(type, role);
                }
                yield null;
            }
            case GROUP -> {
                GroupModel group = groups.getGroupById(realm, id);
                if (group != null) {
                    yield groupReference(group);
                }
                yield null;
            }
        };
    }

    private static Stream<KeycloakReferenceResponse> search(RealmModel realm, RoleProvider roles,
            GroupProvider groups, ResourceType type, String term, int first, int limit) {
        return switch (type) {
            case REALM_ROLE -> realm.searchForRolesStream(term, first, limit)
                    .limit(limit)
                    .map(role -> roleReference(type, role));
            case CLIENT_ROLE -> roles.searchForClientRolesStream(realm, term, Stream.empty(), first, limit)
                    .limit(limit)
                    .map(role -> roleReference(type, role));
            case GROUP -> groups.searchForGroupByNameStream(realm, term, false, first, limit)
                    .limit(limit)
                    .map(KeycloakReferenceSearch::groupReference);
        };
    }

    private static KeycloakReferenceResponse roleReference(ResourceType type, RoleModel role) {
        return new KeycloakReferenceResponse(type, role.getId(), roleDisplayName(type, role),
                Objects.requireNonNullElse(role.getDescription(), ""));
    }

    static String roleDisplayName(ResourceType type, RoleModel role) {
        String name = role.getName();
        if (type == ResourceType.CLIENT_ROLE && role.getContainer() instanceof ClientModel client) {
            name = client.getClientId() + " / " + name;
        }
        return name;
    }

    private static KeycloakReferenceResponse groupReference(GroupModel group) {
        List<String> path = new ArrayList<>();
        GroupModel current = group;
        while (current != null) {
            path.addFirst(current.getName());
            current = current.getParent();
        }
        return new KeycloakReferenceResponse(ResourceType.GROUP, group.getId(), "/" + String.join("/", path), "");
    }
}
