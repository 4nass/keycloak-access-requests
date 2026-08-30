package ch.anass.keycloak.accessrequests.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessRequestQueryTest {

    @Test
    void acceptsRequesterFiltersAndTheMaximumOffset() {
        AccessRequestQuery query = new AccessRequestQuery(
                "realm-1",
                "requester-1",
                DecisionStatus.PENDING,
                ResourceType.GROUP,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-31T23:59:59Z"),
                100,
                100);

        assertEquals(AccessRequestQuery.MAX_OFFSET, query.offset());
    }

    @Test
    void rejectsAnOffsetBeyondTheMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new AccessRequestQuery(
                "realm-1", "requester-1", 101, 100));
    }

    @Test
    void rejectsADateRangeWithAnEndBeforeItsStart() {
        assertThrows(IllegalArgumentException.class, () -> new AccessRequestQuery(
                "realm-1",
                "requester-1",
                null,
                null,
                Instant.parse("2026-08-31T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                0,
                20));
    }
}
