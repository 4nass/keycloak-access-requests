package ch.anass.keycloak.accessrequests.core.domain;

import java.util.Objects;

public record CatalogQuery(
        String realmId,
        ResourceType resourceType,
        String search,
        RiskLevel riskLevel,
        int page,
        int size) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public CatalogQuery {
        realmId = requireText(realmId, "realmId");
        search = normalizeSearch(search);
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

    private static String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        String normalized = search.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
