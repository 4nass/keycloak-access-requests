package ch.anass.keycloak.accessrequests.spi.realm.assurance;

import ch.anass.keycloak.accessrequests.core.domain.approval.ApprovalAssurancePolicy;
import ch.anass.keycloak.accessrequests.core.domain.entitlement.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.ApprovalAssuranceVerifier;
import com.fasterxml.jackson.core.type.TypeReference;
import org.keycloak.authentication.authenticators.util.LoAUtil;
import org.keycloak.models.Constants;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.oidc.utils.AcrUtils;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** Validates the authenticated LoA and its original timestamp, never client-supplied decision data. */
public final class KeycloakApprovalAssuranceVerifier implements ApprovalAssuranceVerifier {

    private final RealmModel realm;
    private final AuthenticationManager.AuthResult authentication;
    private final Clock clock;
    private final RealmApprovalAssurancePolicy policies;

    public KeycloakApprovalAssuranceVerifier(RealmModel realm, AuthenticationManager.AuthResult authentication,
            Clock clock) {
        this.realm = Objects.requireNonNull(realm);
        this.authentication = Objects.requireNonNull(authentication);
        this.clock = Objects.requireNonNull(clock);
        this.policies = new RealmApprovalAssurancePolicy();
    }

    @Override
    public void verify(RiskLevel riskLevel) {
        ApprovalAssurancePolicy.Requirement required = policies.read(realm).requirementFor(riskLevel);
        if (required == null) {
            return;
        }
        // A configured LoA condition is necessary, but operators must still configure the
        // corresponding browser flow to actually perform MFA at that level.
        if (authentication.client() == null) {
            throw new ApprovalAssuranceException("STEP_UP_REQUIRED", required.acr());
        }
        Map<String, Integer> acrMap = AcrUtils.getAcrLoaMap(authentication.client());
        Map<Integer, Integer> configuredMaxAges = LoAUtil.getLoaMaxAgesConfiguredInRealmBrowserFlow(realm);
        if (!Objects.equals(resolveLoa(required.acr(), acrMap), required.loa())) {
            throw new ApprovalAssuranceException("ASSURANCE_NOT_CONFIGURED", null);
        }
        requireRecordedLevel(required, configuredMaxAges);
        if (authentication.token() == null || authentication.session() == null) {
            throw new ApprovalAssuranceException("STEP_UP_REQUIRED", required.acr());
        }
        AuthenticatedClientSessionModel clientSession = authentication.session()
                .getAuthenticatedClientSessionByClient(authentication.client().getId());
        if (clientSession == null) {
            throw new ApprovalAssuranceException("STEP_UP_REQUIRED", required.acr());
        }
        try {
            Map<Integer, Long> levelTimes = JsonSerialization.readValue(
                    authentication.session().getNote(Constants.LOA_MAP), new TypeReference<>() {});
            if (!hasFreshAssurance(required, authentication.token().getAcr(), authentication.token().getIat(),
                    LoAUtil.getCurrentLevelOfAuthentication(clientSession), acrMap, configuredMaxAges,
                    levelTimes, clock.instant().getEpochSecond())) {
                throw new ApprovalAssuranceException("STEP_UP_REQUIRED", required.acr());
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new ApprovalAssuranceException("STEP_UP_REQUIRED", required.acr());
        }
    }

    static void requireRecordedLevel(ApprovalAssurancePolicy.Requirement required,
            Map<Integer, Integer> configuredMaxAges) {
        Integer maxAge = configuredMaxAges.get(required.loa());
        // With Max Age = 0, Keycloak records the LoA for the current authentication
        // but intentionally omits its timestamp from the user session. A refreshed
        // token's iat is not a safe substitute for the time MFA was performed.
        // An ACR-only challenge reuses the level while its flow Max Age remains valid.
        // If that exceeds our policy, a stale proof would be rejected again after login.
        if (maxAge == null || maxAge <= 0 || maxAge > required.maxAgeSeconds()) {
            throw new ApprovalAssuranceException("ASSURANCE_NOT_CONFIGURED", null);
        }
    }

    static boolean hasFreshAssurance(ApprovalAssurancePolicy.Requirement required, String tokenAcr,
            Long tokenIssuedAt, int clientLoa, Map<String, Integer> acrMap,
            Map<Integer, Integer> configuredMaxAges, Map<Integer, Long> levelTimes, long now) {
        if (tokenIssuedAt == null || levelTimes == null) {
            return false;
        }
        Integer provenLoa = resolveLoa(tokenAcr, acrMap);
        if (provenLoa == null || provenLoa < required.loa() || provenLoa > clientLoa) {
            return false;
        }
        Integer configuredMaxAge = configuredMaxAges.get(provenLoa);
        if (configuredMaxAge == null || configuredMaxAge <= 0) {
            return false;
        }
        Long authenticatedAt = levelTimes.get(provenLoa);
        return authenticatedAt != null && authenticatedAt > 0 && authenticatedAt <= tokenIssuedAt
                && tokenIssuedAt <= now && authenticatedAt <= now
                && now - authenticatedAt <= Math.min(required.maxAgeSeconds(), configuredMaxAge);
    }

    private static Integer resolveLoa(String acr, Map<String, Integer> acrMap) {
        if (acr == null) {
            return null;
        }
        Integer mapped = acrMap.get(acr);
        if (mapped != null) {
            return mapped;
        }
        try {
            return Integer.valueOf(acr);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
