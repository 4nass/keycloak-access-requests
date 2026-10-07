package ch.anass.keycloak.accessrequests.core.domain.approval;

/** Server-observed assurance at the instant of a human approval. No token or credential is retained. */
public record ApprovalAssuranceEvidence(String requiredAcr, int requiredLoa, int maxAgeSeconds,
        String observedAcr, int observedLoa, long authenticatedAt, long tokenIssuedAt, long verifiedAt) {

    public ApprovalAssuranceEvidence {
        if (requiredAcr == null || requiredAcr.isBlank() || observedAcr == null || observedAcr.isBlank()
                || requiredLoa < 2 || observedLoa < requiredLoa || maxAgeSeconds < 1
                || authenticatedAt <= 0 || authenticatedAt > tokenIssuedAt || tokenIssuedAt > verifiedAt
                || verifiedAt - authenticatedAt > maxAgeSeconds) {
            throw new IllegalArgumentException("Invalid approval assurance evidence");
        }
    }
}
