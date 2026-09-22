# Installation and upgrades

## Requirements

- Keycloak 26.7.x, validated against 26.7.3;
- Java 21;
- a supported Keycloak database. The provider stores its data in the same database as Keycloak;
- Maven 3.9 or newer when building from source.

The provider JAR must run on the same Keycloak minor line for which it was built. Do not deploy this line to Keycloak 26.5.x or 26.6.x.

For the underlying Keycloak provider lifecycle, see the official [Server Developer Guide](https://www.keycloak.org/docs/latest/server_development/).

## Build the provider

From the repository root:

```bash
mvn --batch-mode --no-transfer-progress package
```

The resulting artifact is:

```text
target/keycloak-access-requests.jar
```

The Maven build also builds the optional Account and Admin Console assets, validates the packaged themes, and places every server and UI component in that one JAR.

## Deploy to a Keycloak distribution

1. Stop Keycloak.
2. Copy `target/keycloak-access-requests.jar` into `<keycloak-home>/providers/`.
3. Run the Keycloak build command:

   ```bash
   <keycloak-home>/bin/kc.sh build
   ```

4. Start Keycloak normally with `kc.sh start`.
5. Configure the realm as described in [Realm configuration](configuration.md).

`start-dev` is suitable only for local development. Use an optimized Keycloak build and `kc.sh start` for production.

## Deploy in a container image

Build the provider first, then add it during the Keycloak image build. This makes the provider part of the optimized image rather than copying it into a running container.

```dockerfile
FROM quay.io/keycloak/keycloak:26.7.3 AS builder

COPY target/keycloak-access-requests.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.7.3

COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

Start the resulting image with your normal production configuration and the `start` command. Keep the Keycloak image tag aligned with the provider baseline.

## First startup and database changes

The JAR registers a Keycloak JPA entity provider and its Liquibase changelog. On startup, Keycloak creates and evolves the provider tables in its own database:

- `AR_ACCESS_REQUEST`
- `AR_ACCESS_REQUEST_HISTORY`
- `AR_ENTITLEMENT`
- `AR_ENTITLEMENT_HISTORY`
- `AR_NOTIFICATION_OUTBOX`

No separate migration command is required. Back up the Keycloak database before every upgrade, review the release notes, and validate the new image against a restored production-like database before rollout.

## Verify the installation

After startup, confirm the provider API in one target realm, then validate the optional integrations that the realm uses:

1. `GET /realms/{realm}/access-requests/catalog` returns `401` without a bearer token and a paged JSON response with a correctly configured token.
2. If using a bundled console theme, **Realm settings → Themes** lists `access-requests` and the expected Account or Admin pages load.
3. If lifecycle e-mails are enabled, test the selected realm SMTP server and send one notification using the selected e-mail-theme integration.

The complete setup for roles, audience, e-mail delivery, and theme localization is in [Realm configuration](configuration.md).

## Upgrade procedure

1. Read the target release notes and confirm it supports the intended Keycloak minor line.
2. Back up the Keycloak database and retain the previously deployed provider image or JAR.
3. Build the replacement JAR with `mvn clean verify`.
4. Replace the JAR in `providers/` or rebuild the container image.
5. Run `kc.sh build` again.
6. Start one canary instance and verify catalog administration, a request, approval, provisioning, lifecycle e-mail delivery, and both console themes.
7. Roll out only after the canary is healthy.

Never replace a provider JAR in a running Keycloak instance. If a compatibility or migration issue occurs, restore the database and the previously tested provider image or JAR together.
