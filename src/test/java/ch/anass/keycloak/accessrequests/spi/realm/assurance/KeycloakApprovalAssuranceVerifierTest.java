package ch.anass.keycloak.accessrequests.spi.realm.assurance;

import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalAssurancePolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakApprovalAssuranceVerifierTest {

    private static final ApprovalAssurancePolicy.Requirement CRITICAL =
            new ApprovalAssurancePolicy.Requirement("strong", 2, 300);
    private static final ApprovalAssurancePolicy.Requirement HIGH =
            new ApprovalAssurancePolicy.Requirement("strong", 2, 1800);
    private static final Map<String, Integer> ACR_LEVELS = Map.of("weak", 1, "strong", 2, "very-strong", 3);
    private static final Map<Integer, Integer> RECORDED_LEVELS = Map.of(2, 600, 3, 600);

    @Test
    void acceptsRecentLevelObtainedBeforeThePresentedToken() {
        assertTrue(hasFreshAssurance("strong", 1000L, 2, Map.of(2, 900L), 1199));
    }

    @Test
    void rejectsOldOrMissingLevelAndTokensIssuedBeforeStepUp() {
        assertFalse(hasFreshAssurance("strong", 1201L, 2, Map.of(2, 900L), 1201));
        assertFalse(hasFreshAssurance("strong", 1000L, 2, Map.of(2, 1001L), 1002));
        assertFalse(hasFreshAssurance("strong", 1000L, 2, Map.of(), 1001));
        assertFalse(hasFreshAssurance("weak", 1000L, 2, Map.of(2, 900L), 1001));
        assertFalse(hasFreshAssurance("strong", 1000L, 2, Map.of(2, 1100L), 1001));
        assertFalse(hasFreshAssurance("strong", 1000L, 2, Map.of(2, 900L), 999));
    }

    @Test
    void acceptsARecentHigherLevelProvenByBothTheTokenAndTheClientSession() {
        assertTrue(hasFreshAssurance("very-strong", 1000L, 3,
                Map.of(2, 100L, 3, 900L), 1001));
        assertTrue(KeycloakApprovalAssuranceVerifier.hasFreshAssurance(CRITICAL, "3", 1000L, 3,
                Map.of(), RECORDED_LEVELS, Map.of(3, 900L), 1001));
    }

    @Test
    void rejectsAnUnprovenOrExpiredHigherLevel() {
        assertFalse(hasFreshAssurance("very-strong", 1000L, 2, Map.of(3, 900L), 1001));
        assertFalse(hasFreshAssurance("very-strong", 1000L, 3, Map.of(2, 900L), 1001));
        assertFalse(hasFreshAssurance("strong", 1000L, 3, Map.of(3, 900L), 1001));
        assertFalse(hasFreshAssurance("very-strong", 1000L, 3, Map.of(2, 900L, 3, 600L), 1001));
        assertFalse(hasFreshAssurance("unmapped", 1000L, 3, Map.of(3, 900L), 1001));
        assertFalse(KeycloakApprovalAssuranceVerifier.hasFreshAssurance(CRITICAL, "very-strong", 1000L, 3,
                Map.of("very-strong", 1), RECORDED_LEVELS, Map.of(3, 900L), 1001));
        assertFalse(KeycloakApprovalAssuranceVerifier.hasFreshAssurance(CRITICAL, "very-strong", 1000L, 3,
                ACR_LEVELS, Map.of(2, 600, 3, 0), Map.of(3, 900L), 1001));
        assertFalse(KeycloakApprovalAssuranceVerifier.hasFreshAssurance(CRITICAL, "very-strong", 1000L, 3,
                ACR_LEVELS, Map.of(2, 600, 3, 30), Map.of(3, 950L), 1001));
    }

    @Test
    void rejectsAOneAuthenticationOnlyLevelBeforeAskingForStepUpAgain() {
        ApprovalAssuranceException missing = assertThrows(ApprovalAssuranceException.class,
                () -> KeycloakApprovalAssuranceVerifier.requireRecordedLevel(CRITICAL, Map.of()));
        assertEquals("ASSURANCE_NOT_CONFIGURED", missing.code());

        ApprovalAssuranceException unrecorded = assertThrows(ApprovalAssuranceException.class,
                () -> KeycloakApprovalAssuranceVerifier.requireRecordedLevel(CRITICAL, Map.of(2, 0)));
        assertEquals("ASSURANCE_NOT_CONFIGURED", unrecorded.code());

        ApprovalAssuranceException tooLong = assertThrows(ApprovalAssuranceException.class,
                () -> KeycloakApprovalAssuranceVerifier.requireRecordedLevel(CRITICAL, Map.of(2, 301)));
        assertEquals("ASSURANCE_NOT_CONFIGURED", tooLong.code());

        KeycloakApprovalAssuranceVerifier.requireRecordedLevel(CRITICAL, Map.of(2, 60));
        KeycloakApprovalAssuranceVerifier.requireRecordedLevel(CRITICAL, Map.of(2, 300));
        KeycloakApprovalAssuranceVerifier.requireRecordedLevel(HIGH, Map.of(2, 1800));
    }

    private static boolean hasFreshAssurance(String tokenAcr, long tokenIssuedAt, int clientLoa,
            Map<Integer, Long> levelTimes, long now) {
        return KeycloakApprovalAssuranceVerifier.hasFreshAssurance(CRITICAL, tokenAcr, tokenIssuedAt,
                clientLoa, ACR_LEVELS, RECORDED_LEVELS, levelTimes, now);
    }
}
