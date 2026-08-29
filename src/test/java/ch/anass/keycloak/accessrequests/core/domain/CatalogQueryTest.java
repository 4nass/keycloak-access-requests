package ch.anass.keycloak.accessrequests.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogQueryTest {

    @Test
    void normalizesBlankSearchTermsAndCalculatesThePageOffset() {
        CatalogQuery query = new CatalogQuery("realm-1", null, "  ", null, 2, 20);

        assertNull(query.search());
        assertEquals(40, query.offset());
    }

    @Test
    void retainsSupportedCatalogFilters() {
        CatalogQuery query = new CatalogQuery(
                "realm-1", ResourceType.CLIENT_ROLE, "Finance", RiskLevel.HIGH, 1, 10);

        assertEquals("realm-1", query.realmId());
        assertEquals(ResourceType.CLIENT_ROLE, query.resourceType());
        assertEquals("Finance", query.search());
        assertEquals(RiskLevel.HIGH, query.riskLevel());
        assertEquals(1, query.page());
        assertEquals(10, query.size());
    }

    @Test
    void rejectsInvalidPaginationAndRealmValues() {
        assertThrows(NullPointerException.class, () -> new CatalogQuery(null, null, null, null, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery(" ", null, null, null, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("realm-1", null, null, null, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("realm-1", null, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CatalogQuery("realm-1", null, null, null, 0, 101));
    }

    @Test
    void rejectsPaginationThatWouldOverflowTheJpaOffset() {
        assertThrows(ArithmeticException.class, () -> new CatalogQuery(
                "realm-1", null, null, null, Integer.MAX_VALUE, 2));
    }
}
