package mx.gilortiz.transactions.transaction;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtros opcionales de {@code GET /transactions} (RN07); {@code null} (o un {@code accountId} en blanco) significa
 * "sin filtro".
 */
record TransactionSearchCriteria(String accountId, TransactionStatus status, TransactionType type) {

    TransactionSearchCriteria {
        if (accountId != null && accountId.isBlank()) {
            accountId = null;
        }
    }

    Specification<Transaction> toSpecification() {
        List<Specification<Transaction>> filters = new ArrayList<>();
        if (accountId != null) {
            filters.add((root, query, builder) -> builder.equal(root.get("accountId"), accountId));
        }
        if (status != null) {
            filters.add((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (type != null) {
            filters.add((root, query, builder) -> builder.equal(root.get("type"), type));
        }
        return Specification.allOf(filters);
    }
}
