package ch.anass.keycloak.accessrequests.spi.realm.resource;

import ch.anass.keycloak.accessrequests.core.domain.entitlement.ResourceType;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaAccessRequestRepository;
import ch.anass.keycloak.accessrequests.persistence.jpa.repository.JpaEntitlementRepository;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

import java.util.HashMap;
import java.util.Map;

/** Resolves bounded Admin page labels without replacing stable IDs used for audit and actions. */
final class AdminDisplayNameResolver implements AdminNameLookup {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final JpaEntitlementRepository entitlements;
    private final JpaAccessRequestRepository requests;
    private final AccessRequestUserNameResolver users;
    private final Map<String, String> names = new HashMap<>();

    AdminDisplayNameResolver(KeycloakSession session, RealmModel realm,
            JpaEntitlementRepository entitlements, JpaAccessRequestRepository requests) {
        this.session = session;
        this.realm = realm;
        this.entitlements = entitlements;
        this.requests = requests;
        this.users = new AccessRequestUserNameResolver(session, realm);
    }

    public String user(String id) {
        return resolve("user:", id, () -> users.resolve(id));
    }

    public String entitlement(String id, String snapshot) {
        String current = resolve("entitlement:", id,
                () -> entitlements.findById(realm.getId(), id).map(value -> value.displayName()).orElse(null));
        return current == null ? snapshot : current;
    }

    public String request(String id) {
        return resolve("request:", id,
                () -> requests.findById(realm.getId(), id).map(value -> value.resourceNameSnapshot()).orElse(null));
    }

    public String role(String id) {
        return resolve("role:", id, () -> {
            RoleModel role = realm.getRoleById(id);
            if (role == null) return null;
            return KeycloakReferenceSearch.roleDisplayName(
                    role.isClientRole() ? ResourceType.CLIENT_ROLE : ResourceType.REALM_ROLE, role);
        });
    }

    public String resource(ResourceType type, String id) {
        if (type == ResourceType.GROUP) {
            return resolve("group:", id, () -> {
                GroupModel group = session.groups().getGroupById(realm, id);
                return group == null ? null : group.getName();
            });
        }
        return role(id);
    }

    private String resolve(String prefix, String id, java.util.function.Supplier<String> lookup) {
        if (id == null || id.isBlank()) return null;
        String resolved = names.computeIfAbsent(prefix + id, ignored -> {
            String value = lookup.get();
            return value == null || value.isBlank() ? "" : value;
        });
        return resolved.isEmpty() ? null : resolved;
    }
}
