package ch.anass.keycloak.accessrequests.core.service;

import ch.anass.keycloak.accessrequests.core.domain.CatalogPage;
import ch.anass.keycloak.accessrequests.core.domain.CatalogQuery;
import ch.anass.keycloak.accessrequests.core.domain.Entitlement;
import ch.anass.keycloak.accessrequests.core.domain.ResourceType;
import ch.anass.keycloak.accessrequests.core.domain.RiskLevel;
import ch.anass.keycloak.accessrequests.core.port.EntitlementRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogServiceTest {

    @Test
    void delegatesTheCatalogQueryToTheEntitlementRepository() {
        CatalogQuery query = new CatalogQuery("realm-1", ResourceType.CLIENT_ROLE, "finance", RiskLevel.LOW, 0, 20);
        CatalogPage expected = new CatalogPage(
                java.util.List.of(Entitlement.create(
                                "entitlement-1",
                                "realm-1",
                                ResourceType.CLIENT_ROLE,
                                "role-1",
                                "Finance Reader",
                                "Read-only access to finance data.",
                                RiskLevel.LOW,
                                "finance-access-approver",
                                Instant.EPOCH)
                        .publish(Instant.EPOCH)),
                0,
                20,
                1);
        RecordingEntitlementRepository repository = new RecordingEntitlementRepository(expected);

        CatalogPage result = new CatalogService(repository).findRequestable(query);

        assertSame(expected, result);
        assertSame(query, repository.lastQuery());
    }

    @Test
    void rejectsNullCatalogQueries() {
        CatalogService service = new CatalogService(new RecordingEntitlementRepository(new CatalogPage(
                java.util.List.of(), 0, 20, 0)));

        assertThrows(NullPointerException.class, () -> service.findRequestable(null));
    }

    private static final class RecordingEntitlementRepository implements EntitlementRepository {

        private final CatalogPage page;
        private CatalogQuery lastQuery;

        private RecordingEntitlementRepository(CatalogPage page) {
            this.page = page;
        }

        @Override
        public Optional<Entitlement> findById(String realmId, String entitlementId) {
            return Optional.empty();
        }

        @Override
        public CatalogPage findRequestable(CatalogQuery query) {
            lastQuery = query;
            return page;
        }

        private CatalogQuery lastQuery() {
            return lastQuery;
        }
    }
}
