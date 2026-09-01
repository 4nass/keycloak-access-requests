package ch.anass.keycloak.accessrequests.core.domain;

import java.util.Objects;

/**
 * A paginated administrative query for all entitlements in one realm, including drafts.
 */
public record EntitlementQuery(String realmId, int page, int size) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public EntitlementQuery {
        realmId = requireText(realmId, "realmId");
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        validateOffset(page, size);
    }

    public int offset() {
        return validateOffset(page, size);
    }

    private static int validateOffset(int page, int size) {
        try {
            return Math.multiplyExact(page, size);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("page and size are too large", exception);
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
