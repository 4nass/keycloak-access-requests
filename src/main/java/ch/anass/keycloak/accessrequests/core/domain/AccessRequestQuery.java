package ch.anass.keycloak.accessrequests.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A paginated query for one requester's access requests in one realm.
 */
public record AccessRequestQuery(
        String realmId,
        String requesterId,
        DecisionStatus decisionStatus,
        ResourceType resourceType,
        Instant from,
        Instant to,
        int page,
        int size) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_OFFSET = 10_000;

    public AccessRequestQuery {
        realmId = requireText(realmId, "realmId");
        requesterId = requireText(requesterId, "requesterId");
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        validateOffset(page, size);
    }

    public AccessRequestQuery(String realmId, String requesterId, int page, int size) {
        this(realmId, requesterId, null, null, null, null, page, size);
    }

    public int offset() {
        return validateOffset(page, size);
    }

    private static int validateOffset(int page, int size) {
        try {
            int offset = Math.multiplyExact(page, size);
            if (offset > MAX_OFFSET) {
                throw new IllegalArgumentException("page and size exceed the maximum offset of " + MAX_OFFSET);
            }
            return offset;
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
