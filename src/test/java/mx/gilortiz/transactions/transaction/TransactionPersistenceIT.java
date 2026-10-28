package mx.gilortiz.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Mapeo de la entidad contra el esquema real de Flyway (Hibernate en {@code validate}) y UNIQUE de la llave.
 */
@SpringBootTest
@Testcontainers
class TransactionPersistenceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final TransactionRepository repository;

    @Autowired
    TransactionPersistenceIT(TransactionRepository repository) {
        this.repository = repository;
    }

    @BeforeEach
    void cleanTable() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("RN05: una transacción PENDING que pasa a EXECUTED se relee con providerTransactionId y balanceAfter")
    void pendingThenExecutedRoundTrip() {
        Transaction pending = repository.saveAndFlush(Transaction.pending(command("key-1")));
        assertThat(repository.findById(pending.id())).get()
                .extracting(Transaction::status).isEqualTo(TransactionStatus.PENDING);

        pending.markExecuted("txn-789", new BigDecimal("5500.00"));
        assertThat(complete(pending)).isEqualTo(1);

        Transaction stored = repository.findById(pending.id()).orElseThrow();
        assertThat(stored.id().version()).isEqualTo(7);
        assertThat(stored.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(stored.accountId()).isEqualTo("acc-123456");
        assertThat(stored.type()).isEqualTo(TransactionType.CREDIT);
        assertThat(stored.amount()).isEqualByComparingTo("1500.00");
        assertThat(stored.currency()).isEqualTo("MXN");
        assertThat(stored.description()).isEqualTo("Transferencia recibida");
        assertThat(stored.providerTransactionId()).isEqualTo("txn-789");
        assertThat(stored.balanceAfter()).isEqualByComparingTo("5500.00");
        assertThat(stored.code()).isNull();
        assertThat(stored.message()).isNull();
        assertThat(stored.idempotencyKey()).isEqualTo("key-1");
        assertThat(stored.createdAt()).isEqualTo(pending.createdAt());
    }

    @Test
    @DisplayName("RN06: una transacción REJECTED se relee con el code y message del proveedor")
    void rejectedRoundTrip() {
        Transaction pending = repository.saveAndFlush(Transaction.pending(command(null)));
        pending.markRejected("INSUFFICIENT_FUNDS",
                "The account does not have enough balance to complete the transaction");
        assertThat(complete(pending)).isEqualTo(1);

        Transaction stored = repository.findById(pending.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(stored.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(stored.message()).isEqualTo("The account does not have enough balance to complete the transaction");
        assertThat(stored.providerTransactionId()).isNull();
        assertThat(stored.balanceAfter()).isNull();
    }

    @Test
    @DisplayName("una segunda inserción con la misma Idempotency-Key viola el UNIQUE y la primera es la única")
    void duplicateIdempotencyKeyViolatesUnique() {
        Transaction first = repository.saveAndFlush(Transaction.pending(command("key-1")));

        assertThatThrownBy(() -> repository.saveAndFlush(Transaction.pending(command("key-1"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByIdempotencyKey("key-1")).get()
                .extracting(Transaction::id).isEqualTo(first.id());
    }

    @Test
    @DisplayName("varias transacciones sin Idempotency-Key conviven: el UNIQUE no aplica a NULL")
    void transactionsWithoutKeyDoNotCollide() {
        repository.saveAndFlush(Transaction.pending(command(null)));
        repository.saveAndFlush(Transaction.pending(command(null)));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("el UPDATE final solo aplica a una fila PENDING: un segundo intento actualiza 0 filas")
    void completePendingOnlyUpdatesPendingRow() {
        Transaction pending = repository.saveAndFlush(Transaction.pending(command(null)));
        pending.markExecuted("txn-789", new BigDecimal("5500.00"));
        assertThat(complete(pending)).isEqualTo(1);

        int secondUpdate = repository.completePending(pending.id(), TransactionStatus.FAILED, null, null,
                "PROVIDER_UNAVAILABLE", "otro resultado");

        assertThat(secondUpdate).isZero();
        Transaction stored = repository.findById(pending.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(stored.code()).isNull();
    }

    @Test
    @DisplayName("un code y un message del proveedor más largos que sus columnas se guardan recortados sin error")
    void longProviderCodeAndMessageArePersisted() {
        Transaction pending = repository.saveAndFlush(Transaction.pending(command(null)));
        pending.markRejected("C".repeat(150), "m".repeat(800));

        assertThat(complete(pending)).isEqualTo(1);

        Transaction stored = repository.findById(pending.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(stored.code()).isEqualTo("C".repeat(Transaction.CODE_MAX_LENGTH));
        assertThat(stored.message()).isEqualTo("m".repeat(Transaction.MESSAGE_MAX_LENGTH));
    }

    @Test
    @DisplayName("una aprobación en el límite de las columnas (id de 100, balance de 17 enteros) se guarda intacta")
    void executionAtColumnBoundsIsPersisted() {
        Transaction pending = repository.saveAndFlush(Transaction.pending(command(null)));
        pending.markExecuted("t".repeat(100), new BigDecimal("99999999999999999.99"));

        assertThat(complete(pending)).isEqualTo(1);

        Transaction stored = repository.findById(pending.id()).orElseThrow();
        assertThat(stored.providerTransactionId()).hasSize(100);
        assertThat(stored.balanceAfter()).isEqualByComparingTo("99999999999999999.99");
    }

    private int complete(Transaction transaction) {
        return repository.completePending(transaction.id(), transaction.status(),
                transaction.providerTransactionId(), transaction.balanceAfter(), transaction.code(),
                transaction.message());
    }

    private static ExecuteTransactionCommand command(String idempotencyKey) {
        return new ExecuteTransactionCommand("acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN",
                "Transferencia recibida", idempotencyKey);
    }
}
