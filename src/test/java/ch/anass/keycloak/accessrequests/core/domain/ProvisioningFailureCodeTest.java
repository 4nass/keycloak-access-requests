package ch.anass.keycloak.accessrequests.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProvisioningFailureCodeTest {

    @Test
    void readsOnlyRecognizedStoredCodes() {
        assertEquals(ProvisioningFailureCode.RESOURCE_MISSING,
                ProvisioningFailureCode.fromStoredValue("RESOURCE_MISSING"));
        assertEquals(ProvisioningFailureCode.UNKNOWN, ProvisioningFailureCode.fromStoredValue(null));
        assertEquals(ProvisioningFailureCode.UNKNOWN,
                ProvisioningFailureCode.fromStoredValue("Internal exception details"));
    }

    @Test
    void requiresAClassificationForFailedResults() {
        assertThrows(NullPointerException.class,
                () -> ProvisioningResult.failed(null, "An internal failure."));
        assertEquals(ProvisioningFailureCode.UNKNOWN,
                ProvisioningResult.failed("Legacy failure.").failureCode());
    }
}
