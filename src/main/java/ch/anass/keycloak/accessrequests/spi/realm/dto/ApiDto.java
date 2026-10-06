package ch.anass.keycloak.accessrequests.spi.realm.dto;

/** JSON payloads shared across administrative surfaces. */
public final class ApiDto {

    private ApiDto() {
    }

    public record AdminCapabilitiesResponse(
            boolean canManageCatalog, boolean canManageNotifications,
            boolean canManageProvisioningFailures, boolean canManageAssurancePolicy,
            boolean canViewEvents) {
    }

    public record ErrorResponse(String code, String message, String requestId) {
    }
}
