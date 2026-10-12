package ch.anass.keycloak.accessrequests.spi.realm.dto;

import java.util.List;

/** Read-only cross-source incident worklist; mutations remain on their existing endpoints. */
public final class IncidentDto {

    private IncidentDto() {
    }

    public record IncidentListResponse(List<IncidentResponse> items, int size, String nextCursor) {
    }

    public record IncidentResponse(String type, String id, String occurredAt, String state,
            NotificationDto.NotificationDeliveryResponse notification,
            ProvisioningDto.FailedProvisioningRequestResponse provisioning,
            RevocationDto.RevocationFailureResponse revocation) {
    }
}
