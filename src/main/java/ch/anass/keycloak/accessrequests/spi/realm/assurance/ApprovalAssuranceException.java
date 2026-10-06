package ch.anass.keycloak.accessrequests.spi.realm.assurance;

public final class ApprovalAssuranceException extends RuntimeException {

    private final String code;
    private final String requiredAcr;

    public ApprovalAssuranceException(String code, String requiredAcr) {
        super(code);
        this.code = code;
        this.requiredAcr = requiredAcr;
    }

    public String code() {
        return code;
    }

    public String requiredAcr() {
        return requiredAcr;
    }
}
