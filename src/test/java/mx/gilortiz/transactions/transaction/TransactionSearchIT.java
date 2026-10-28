package mx.gilortiz.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mx.gilortiz.transactions.DatabaseCleaner;
import mx.gilortiz.transactions.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RN07 contra Postgres real: filtros, paginación y orden estable {@code createdAt desc, id desc}.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TransactionSearchIT {

    private static final Instant BASE = Instant.parse("2025-03-15T10:00:00Z");
    private static final TransactionSearchCriteria NO_FILTERS = new TransactionSearchCriteria(null, null, null);

    private final TransactionService service;
    private final TransactionRepository repository;
    private final DatabaseCleaner databaseCleaner;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionSearchIT(TransactionService service, TransactionRepository repository,
                        DatabaseCleaner databaseCleaner, JdbcTemplate jdbcTemplate) {
        this.service = service;
        this.repository = repository;
        this.databaseCleaner = databaseCleaner;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void cleanTable() {
        databaseCleaner.clean();
    }

    @Test
    @DisplayName("RN07: filtra por accountId")
    void filtersByAccountId() {
        Transaction expected = insert("acc-1", TransactionType.CREDIT, TransactionStatus.EXECUTED, 0);
        insert("acc-2", TransactionType.CREDIT, TransactionStatus.EXECUTED, 1);

        Page<Transaction> page = service.search(new TransactionSearchCriteria("acc-1", null, null), 0, 10);

        assertThat(page.getContent()).extracting(Transaction::id).containsExactly(expected.id());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("RN07: filtra por status")
    void filtersByStatus() {
        insert("acc-1", TransactionType.CREDIT, TransactionStatus.EXECUTED, 0);
        Transaction rejected = insert("acc-1", TransactionType.CREDIT, TransactionStatus.REJECTED, 1);

        Page<Transaction> page = service.search(
                new TransactionSearchCriteria(null, TransactionStatus.REJECTED, null), 0, 10);

        assertThat(page.getContent()).extracting(Transaction::id).containsExactly(rejected.id());
    }

    @Test
    @DisplayName("RN07: filtra por type")
    void filtersByType() {
        Transaction debit = insert("acc-1", TransactionType.DEBIT, TransactionStatus.EXECUTED, 0);
        insert("acc-1", TransactionType.CREDIT, TransactionStatus.EXECUTED, 1);

        Page<Transaction> page = service.search(new TransactionSearchCriteria(null, null, TransactionType.DEBIT), 0, 10);

        assertThat(page.getContent()).extracting(Transaction::id).containsExactly(debit.id());
    }

    @Test
    @DisplayName("RN07: los filtros se combinan con AND")
    void combinesFilters() {
        Transaction match = insert("acc-1", TransactionType.DEBIT, TransactionStatus.EXECUTED, 0);
        insert("acc-1", TransactionType.DEBIT, TransactionStatus.REJECTED, 1);
        insert("acc-1", TransactionType.CREDIT, TransactionStatus.EXECUTED, 2);
        insert("acc-2", TransactionType.DEBIT, TransactionStatus.EXECUTED, 3);

        Page<Transaction> page = service.search(
                new TransactionSearchCriteria("acc-1", TransactionStatus.EXECUTED, TransactionType.DEBIT), 0, 10);

        assertThat(page.getContent()).extracting(Transaction::id).containsExactly(match.id());
    }

    @Test
    @DisplayName("RN07: un accountId en blanco equivale a no filtrar")
    void blankAccountIdDoesNotFilter() {
        insert("acc-1", TransactionType.CREDIT, TransactionStatus.EXECUTED, 0);
        insert("acc-2", TransactionType.CREDIT, TransactionStatus.EXECUTED, 1);

        Page<Transaction> page = service.search(new TransactionSearchCriteria(" ", null, null), 0, 10);

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("RN07: las páginas recorren todas las filas sin repetir ni perder, aun con createdAt empatado")
    void pagesAreStableWithTiedCreatedAt() {
        for (int i = 0; i < 7; i++) {
            // Tres filas por instante: el id decide el orden entre empatadas.
            insert("acc-1", TransactionType.CREDIT, TransactionStatus.EXECUTED, i / 3);
        }
        // Oráculo independiente del código bajo prueba: el orden lo da Postgres (uuid se compara sin signo).
        List<UUID> expectedOrder = jdbcTemplate.queryForList(
                "SELECT id FROM transactions ORDER BY created_at DESC, id DESC", UUID.class);
        assertThat(expectedOrder).hasSize(7);

        List<UUID> visited = new ArrayList<>();
        Page<Transaction> page;
        int pageNumber = 0;
        do {
            page = service.search(NO_FILTERS, pageNumber++, 3);
            page.getContent().forEach(transaction -> visited.add(transaction.id()));
        } while (page.hasNext());

        assertThat(visited).containsExactlyElementsOf(expectedOrder);
        assertThat(page.getTotalElements()).isEqualTo(7);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("RN07: una página más allá del final devuelve contenido vacío con el total correcto")
    void pageBeyondEndIsEmpty() {
        insert("acc-1", TransactionType.CREDIT, TransactionStatus.EXECUTED, 0);

        Page<Transaction> page = service.search(NO_FILTERS, 5, 10);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    private Transaction insert(String accountId, TransactionType type, TransactionStatus finalStatus,
                               int secondsAfterBase) {
        Transaction transaction = Transaction.pending(new ExecuteTransactionCommand(accountId, type,
                new BigDecimal("1500.00"), "MXN", "Transferencia recibida", null));
        ReflectionTestUtils.setField(transaction, "createdAt", BASE.plusSeconds(secondsAfterBase));
        Transaction saved = repository.saveAndFlush(transaction);
        switch (finalStatus) {
            case EXECUTED -> saved.markExecuted("txn-" + saved.id(), new BigDecimal("5500.00"));
            case REJECTED -> saved.markRejected("INSUFFICIENT_FUNDS", "Sin saldo");
            default -> throw new IllegalArgumentException("estado no usado en este test: " + finalStatus);
        }
        repository.completePending(saved.id(), saved.status(), saved.providerTransactionId(), saved.balanceAfter(),
                saved.code(), saved.message());
        return saved;
    }
}
