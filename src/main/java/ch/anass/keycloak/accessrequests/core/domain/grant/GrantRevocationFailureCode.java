package ch.anass.keycloak.accessrequests.core.domain.grant;

/** Safe, stable reason codes; technical causes remain in server logs. */
public enum GrantRevocationFailureCode {
    AUTHORITY_UNVERIFIABLE,
    REMOVAL_FAILED,
    UNEXPECTED_FAILURE;

    public static GrantRevocationFailureCode fromStoredValue(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return UNEXPECTED_FAILURE;
        }
    }
}
