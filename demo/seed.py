"""Create the demo catalog through the provider API after realm import.

The realm JSON deliberately owns only native Keycloak objects. This one-shot
process resolves their IDs and lets the extension create its own JPA records,
delivery groups, role mappings, and audit events transactionally.
"""

import json
import os
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


BASE_URL = os.environ.get("KEYCLOAK_URL", "http://keycloak:8080").rstrip("/")
REALM = os.environ.get("DEMO_REALM", "access-requests-demo")
ADMIN_USER = os.environ.get("DEMO_ADMIN_USERNAME", "demo-admin")
ADMIN_PASSWORD = os.environ["DEMO_ADMIN_PASSWORD"]
TIMEOUT = int(os.environ.get("SEED_TIMEOUT_SECONDS", "300"))
REALM_PATH = quote(REALM, safe="")
DEMO_USERS = (
    "demo-admin",
    "demo-approver",
    "demo-mfa-approver",
    "demo-requester",
    "demo-requester-two",
)


def request(method, path, token=None, payload=None, form=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if form is not None:
        data = urlencode(form).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    else:
        data = None
    http_request = Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urlopen(http_request, timeout=15) as response:
            body = response.read()
            return json.loads(body) if body else None
    except HTTPError as error:
        detail = error.read(1000).decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path}: HTTP {error.code}: {detail}") from error


def await_admin_token():
    deadline = time.monotonic() + TIMEOUT
    token_path = f"/realms/{REALM_PATH}/protocol/openid-connect/token"
    last_error = None
    while time.monotonic() < deadline:
        try:
            result = request("POST", token_path, form={
                "client_id": "admin-cli",
                "grant_type": "password",
                "username": ADMIN_USER,
                "password": ADMIN_PASSWORD,
            })
            return result["access_token"]
        except (RuntimeError, URLError, TimeoutError, KeyError) as error:
            last_error = error
            time.sleep(3)
    raise RuntimeError(f"Keycloak or demo realm did not become ready: {last_error}")


def realm_role(token, name):
    return request("GET", f"/admin/realms/{REALM_PATH}/roles/{quote(name, safe='')}", token)


def client(token, client_id):
    matches = request("GET", f"/admin/realms/{REALM_PATH}/clients?{urlencode({'clientId': client_id})}", token)
    exact = [candidate for candidate in matches if candidate["clientId"] == client_id]
    if len(exact) != 1:
        raise RuntimeError(f"Expected exactly one client named {client_id}, found {len(exact)}")
    return exact[0]


def ensure_account_roles(token):
    """Realm-imported users do not inherit Keycloak's default role automatically."""
    default_role = realm_role(token, f"default-roles-{REALM}")
    account_client_id = client(token, "account")["id"]
    for username in DEMO_USERS:
        users = request("GET", f"/admin/realms/{REALM_PATH}/users?"
                        f"{urlencode({'username': username, 'exact': 'true'})}", token)
        exact = [user for user in users if user["username"] == username]
        if len(exact) != 1:
            raise RuntimeError(f"Expected exactly one demo user named {username}")
        user_path = f"/admin/realms/{REALM_PATH}/users/{exact[0]['id']}"
        direct_roles = request("GET", f"{user_path}/role-mappings/realm", token)
        if not any(role["id"] == default_role["id"] for role in direct_roles):
            request("POST", f"{user_path}/role-mappings/realm", token,
                    payload=[{"id": default_role["id"], "name": default_role["name"]}])
            print(f"Restored default Account roles for {username}", flush=True)
        effective_roles = request(
            "GET", f"{user_path}/role-mappings/clients/{account_client_id}/composite", token)
        if not {"manage-account", "view-profile"}.issubset(
                {role["name"] for role in effective_roles}):
            raise RuntimeError(f"Demo user {username} lacks native Account Console roles")


def role_mapping(token, spec):
    if spec["type"] == "REALM_ROLE":
        role = realm_role(token, spec["name"])
    elif spec["type"] == "CLIENT_ROLE":
        client_id = client(token, spec["clientId"])["id"]
        role = request("GET", f"/admin/realms/{REALM_PATH}/clients/{client_id}/roles/"
                       f"{quote(spec['name'], safe='')}", token)
    else:
        raise ValueError(f"Unsupported role type: {spec['type']}")
    return {"type": spec["type"], "roleId": role["id"]}


def ensure_account_audience(token):
    account_client_id = client(token, "account-console")["id"]
    path = f"/admin/realms/{REALM_PATH}/clients/{account_client_id}/protocol-mappers/models"
    mappers = request("GET", path, token)
    if any(mapper.get("name") == "access-requests-api-audience" for mapper in mappers):
        print("Account Console audience already configured", flush=True)
        return
    request("POST", path, token, payload={
        "name": "access-requests-api-audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "config": {
            "included.client.audience": "access-requests-api",
            "access.token.claim": "true",
            "id.token.claim": "false",
            "introspection.token.claim": "true",
        },
    })
    print("Configured Account Console API audience", flush=True)


def all_entitlements(token):
    path = f"/realms/{REALM_PATH}/access-requests/admin/entitlements"
    items = []
    page = 0
    while True:
        result = request("GET", f"{path}?page={page}&size=100", token)
        items.extend(result["items"])
        if len(items) >= result["total"]:
            return items
        page += 1


def seed_package(token, spec, existing):
    name = spec["displayName"]
    matches = [item for item in existing if item["displayName"] == name]
    if len(matches) > 1:
        raise RuntimeError(f"More than one entitlement named {name}")

    role_mappings = [role_mapping(token, role) for role in spec["roles"]]
    approver_role_id = realm_role(token, spec["approverRole"])["id"]
    package_path = f"/realms/{REALM_PATH}/access-requests/admin/access-packages"
    entitlement_path = f"/realms/{REALM_PATH}/access-requests/admin/entitlements"

    if matches:
        entitlement = matches[0]
        package = request("GET", f"{package_path}/{entitlement['id']}", token)
        actual = [{"type": mapping["type"], "roleId": mapping["roleId"]}
                  for mapping in package["roleMappings"]]
        if actual != role_mappings or entitlement["riskLevel"] != spec["riskLevel"]:
            raise RuntimeError(f"Existing package {name} differs from the demo definition")
        if entitlement["requestable"]:
            print(f"Already published: {name}", flush=True)
            return
        print(f"Publishing existing draft: {name}", flush=True)
    else:
        payload = {
            "displayName": name,
            "description": spec["description"],
            "riskLevel": spec["riskLevel"],
            "approverRoleId": approver_role_id,
            "defaultDurationSeconds": spec["defaultDurationSeconds"],
            "maxDurationSeconds": spec["maxDurationSeconds"],
            "allowPermanent": spec["allowPermanent"],
            "autoApprove": spec["autoApprove"],
            "roleMappings": role_mappings,
        }
        entitlement = request("POST", package_path, token, payload=payload)
        print(f"Created draft: {name}", flush=True)

    published = request("PUT", f"{entitlement_path}/{entitlement['id']}", token, payload={
        "displayName": name,
        "description": spec["description"],
        "riskLevel": spec["riskLevel"],
        "approverRoleId": approver_role_id,
        "defaultDurationSeconds": spec["defaultDurationSeconds"],
        "maxDurationSeconds": spec["maxDurationSeconds"],
        "allowPermanent": spec["allowPermanent"],
        "autoApprove": spec["autoApprove"],
        "requestable": True,
        "version": entitlement["version"],
    })
    if not published["requestable"]:
        raise RuntimeError(f"Package {name} was not published")
    print(f"Published: {name}", flush=True)


def main():
    token = await_admin_token()
    print(f"Demo realm {REALM} is ready", flush=True)
    request("GET", f"/admin/realms/{REALM_PATH}", token)
    ensure_account_roles(token)
    ensure_account_audience(token)
    existing = all_entitlements(token)
    with open("/demo/catalog.json", encoding="utf-8") as catalog_file:
        catalog = json.load(catalog_file)
    for spec in catalog:
        seed_package(token, spec, existing)
    print(f"Demo catalog ready: {len(catalog)} packages", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Demo seed failed: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
