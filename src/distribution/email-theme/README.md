# Access requests e-mail theme

This bundle contains the e-mail theme shipped with the Keycloak Access Requests
provider. It includes English, French, German, and Spanish message bundles.

## Install the complete theme

Extract this archive into the Keycloak themes directory. It creates:

    themes/access-requests/email/

Deploy the provider JAR as described in the installation guide, then select
access-requests as the realm's e-mail theme in Realm settings > Themes. The
theme uses Keycloak's built-in parent theme and is intended as a working
default. Customize it before use if your organization requires specific
branding or wording.

## Integrate the templates into an existing e-mail theme

To preserve an existing theme and its branding:

1. Copy access-requests/email/html/ and access-requests/email/text/ into
   the corresponding html/ and text/ directories of your theme.
2. Merge the keys from each
   access-requests/email/messages/messages_*.properties file into the matching
   messages/messages_*.properties file in your theme. Keep your existing keys;
   do not replace the whole message bundle.
3. Keep your theme's existing theme.properties and parent configuration.
4. Ensure the selected theme or its parent provides Keycloak's template.ftl,
   which the supplied HTML templates import.
5. Select your existing e-mail theme in Realm settings > Themes.

Do not copy this bundle's theme.properties over an existing theme's file.

Configure the realm SMTP server in Realm settings > Email. Enable realm
internationalization and select the supported locales in Realm settings >
Localization if you use translated messages.
