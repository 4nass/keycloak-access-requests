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
        Math.multiplyExact(page, size);
    }

    public int offset() {
        return Math.multiplyExact(page, size);
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
