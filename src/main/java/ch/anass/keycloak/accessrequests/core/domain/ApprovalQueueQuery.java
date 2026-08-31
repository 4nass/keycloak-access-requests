package ch.anass.keycloak.accessrequests.core.domain;

import java.util.Objects;
import java.util.Set;

/**
 * A paginated query for the pending requests an approver may decide in one realm.
 */
public record ApprovalQueueQuery(
        String realmId,
        String approverId,
        Set<String> approverRoleIds,
        int page,
        int size) {

    public ApprovalQueueQuery {
        realmId = requireText(realmId, "realmId");
        approverId = requireText(approverId, "approverId");
        approverRoleIds = Set.copyOf(Objects.requireNonNull(
                approverRoleIds, "approverRoleIds must not be null"));
        if (approverRoleIds.stream().anyMatch(roleId -> roleId == null || roleId.isBlank())) {
            throw new IllegalArgumentException("approverRoleIds must not contain blank values");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > AccessRequestQuery.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between 1 and " + AccessRequestQuery.MAX_PAGE_SIZE);
        }
        validateOffset(page, size);
    }

    public int offset() {
        return validateOffset(page, size);
    }

    private static int validateOffset(int page, int size) {
        try {
            int offset = Math.multiplyExact(page, size);
            if (offset > AccessRequestQuery.MAX_OFFSET) {
                throw new IllegalArgumentException(
                        "page and size exceed the maximum offset of " + AccessRequestQuery.MAX_OFFSET);
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
