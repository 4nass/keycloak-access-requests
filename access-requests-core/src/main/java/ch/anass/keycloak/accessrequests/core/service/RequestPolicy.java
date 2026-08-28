package ch.anass.keycloak.accessrequests.core.service;

public final class RequestPolicy {

    private final int minimumJustificationLength;
    private final int maximumJustificationLength;

    public RequestPolicy(int minimumJustificationLength, int maximumJustificationLength) {
        if (minimumJustificationLength < 0 || maximumJustificationLength < minimumJustificationLength) {
            throw new IllegalArgumentException("Invalid justification length bounds");
        }
        this.minimumJustificationLength = minimumJustificationLength;
        this.maximumJustificationLength = maximumJustificationLength;
    }

    public void validateJustification(String justification) {
        if (justification == null
                || justification.length() < minimumJustificationLength
                || justification.length() > maximumJustificationLength) {
            throw new InvalidJustificationException(
                    "Justification length must be between "
                            + minimumJustificationLength
                            + " and "
                            + maximumJustificationLength
                            + " characters.");
        }
    }
}
