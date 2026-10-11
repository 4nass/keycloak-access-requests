# Local demo environment

From the repository root, run:

```bash
docker compose up --build -d
docker compose logs -f seed
```

The `seed` container exits with code 0 after the catalog is ready. The first build compiles the
provider and both console themes inside Docker; no local Maven or Node.js installation is needed.

| Surface | URL |
| --- | --- |
| Account Console | http://localhost:8080/realms/access-requests-demo/account/ |
| Admin Console | http://localhost:8080/admin/access-requests-demo/console/ |
| Mailpit inbox | http://localhost:8025/ |

Default demo passwords (override with `DEMO_ADMIN_PASSWORD` and `DEMO_USER_PASSWORD` before the
first import):

| User | Password | Purpose |
| --- | --- | --- |
| `demo-admin` | `demo-admin-only` | Realm administrator; manages the catalog |
| `demo-approver` | `demo-user-only` | LOW and MEDIUM approvals |
| `demo-mfa-approver` | `demo-user-only` | HIGH and CRITICAL approvals; enrolls OTP on first sign-in |
| `demo-requester` | `demo-user-only` | Requests access |
| `demo-requester-two` | `demo-user-only` | Second requester for authorization checks |

`bootstrap` in the master realm uses `DEMO_BOOTSTRAP_PASSWORD` (default `demo-bootstrap-only`)
and is only a recovery account. The demo realm is imported separately.

The imported realm selects the bundled `access-requests` Account, Admin, and e-mail themes, enables
Admin Events, configures SMTP to Mailpit, and binds a two-level browser flow. LoA 1 uses a password;
LoA 2 requires OTP and remains reusable for at most 300 seconds. The sensitive approver must
enroll a TOTP authenticator at first sign-in. HIGH and CRITICAL approvals fail closed until that
enrollment and step-up are complete. The demo also configures the Account Console's API audience.

It also checks that imported demo users have Keycloak's default Account Console roles. Re-running
`docker compose run --rm seed` repairs those mappings in an existing demo database without erasing
requests or catalog data. Native Account Console links follow Keycloak's realm feature flags;
resource sharing is hidden while User-Managed Access is disabled.

The sidecar creates four published access packages through the extension API:

| Package | Risk | Granted role | Decision | Default / maximum |
| --- | --- | --- | --- | --- |
| Demo Wiki Reader | LOW | Realm `demo-wiki-reader` | Automatic | 30 / 90 days; permanent allowed |
| Demo Project Editor | MEDIUM | Client `demo-app:project-editor` | `demo-approver` | 7 / 30 days |
| Demo Operations Deployer | HIGH | Realm `demo-ops-deployer` | `demo-mfa-approver` + OTP | 8 / 24 hours |
| Demo Production Administrator | CRITICAL | Client `demo-app:production-admin` | `demo-mfa-approver` + fresh OTP | 1 / 4 hours |

Each package has its own extension-created `AR_PKG_*` group. The seed resolves role IDs at runtime,
creates only missing packages, and publishes drafts left by an interrupted seed. Re-run it with
`docker compose run --rm seed` if needed.

This compose file is **for local demonstration only**. It binds HTTP, SMTP, and Mailpit to
`127.0.0.1`, uses intentionally weak default passwords, and runs Keycloak in development mode.
Do not expose it to a network or use its realm export in production. `docker compose down` keeps
the PostgreSQL volume; `docker compose down -v` also **deletes all demo realm data**. Keycloak's
`--import-realm` skips an existing realm, so changing the JSON alone does not rewrite a running
demo database.
