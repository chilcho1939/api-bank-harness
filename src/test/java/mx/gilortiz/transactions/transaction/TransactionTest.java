package mx.gilortiz.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionTest {

    private static final ExecuteTransactionCommand COMMAND = new ExecuteTransactionCommand(
            "acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN", "Transferencia recibida", "key-1");

    @Test
    @DisplayName("una transacción nueva nace PENDING con los datos de la solicitud y sin resultado del proveedor")
    void newTransactionIsPending() {
        Transaction transaction = Transaction.pending(COMMAND);

        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(transaction.accountId()).isEqualTo("acc-123456");
        assertThat(transaction.type()).isEqualTo(TransactionType.CREDIT);
        assertThat(transaction.amount()).isEqualByComparingTo("1500.00");
        assertThat(transaction.currency()).isEqualTo("MXN");
        assertThat(transaction.description()).isEqualTo("Transferencia recibida");
        assertThat(transaction.idempotencyKey()).isEqualTo("key-1");
        assertThat(transaction.createdAt()).isNotNull()
                .isEqualTo(transaction.createdAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(transaction.providerTransactionId()).isNull();
        assertThat(transaction.balanceAfter()).isNull();
        assertThat(transaction.code()).isNull();
        assertThat(transaction.message()).isNull();
    }

    @Test
    @DisplayName("RN05: aprobada pasa a EXECUTED con providerTransactionId y balanceAfter")
    void markExecutedStoresProviderData() {
        Transaction transaction = Transaction.pending(COMMAND);

        transaction.markExecuted("txn-789", new BigDecimal("5500.00"));

        assertThat(transaction.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(transaction.providerTransactionId()).isEqualTo("txn-789");
        assertThat(transaction.balanceAfter()).isEqualByComparingTo("5500.00");
        assertThat(transaction.code()).isNull();
        assertThat(transaction.message()).isNull();
    }

    static Stream<Arguments> errorTransitions() {
        return Stream.of(
                Arguments.of(TransactionStatus.REJECTED, (TransitionWithError) Transaction::markRejected),
                Arguments.of(TransactionStatus.FAILED, (TransitionWithError) Transaction::markFailed),
                Arguments.of(TransactionStatus.UNKNOWN, (TransitionWithError) Transaction::markUnknown));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorTransitions")
    @DisplayName("RN06: rechazada, fallida o desconocida guarda el code y message del resultado")
    void errorTransitionStoresCodeAndMessage(TransactionStatus expected, TransitionWithError transition) {
        Transaction transaction = Transaction.pending(COMMAND);

        transition.apply(transaction, "INSUFFICIENT_FUNDS",
                "The account does not have enough balance to complete the transaction");

        assertThat(transaction.status()).isEqualTo(expected);
        assertThat(transaction.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(transaction.message())
                .isEqualTo("The account does not have enough balance to complete the transaction");
        assertThat(transaction.providerTransactionId()).isNull();
        assertThat(transaction.balanceAfter()).isNull();
    }

    static Stream<Arguments> allTransitions() {
        return Stream.of(
                Arguments.of("EXECUTED", (Consumer<Transaction>) t -> t.markExecuted("txn-1", BigDecimal.TEN)),
                Arguments.of("REJECTED", (Consumer<Transaction>) t -> t.markRejected("C", "m")),
                Arguments.of("FAILED", (Consumer<Transaction>) t -> t.markFailed("C", "m")),
                Arguments.of("UNKNOWN", (Consumer<Transaction>) t -> t.markUnknown("C", "m")));
    }

    @ParameterizedTest(name = "desde {0}")
    @MethodSource("allTransitions")
    @DisplayName("una transacción en estado final no puede cambiar de estado otra vez")
    void finalStateCannotTransitionAgain(String firstState, Consumer<Transaction> firstTransition) {
        Transaction transaction = Transaction.pending(COMMAND);
        firstTransition.accept(transaction);

        allTransitions().map(arguments -> (Consumer<Transaction>) arguments.get()[1]).forEach(second ->
                assertThatThrownBy(() -> second.accept(transaction))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ya está en " + firstState));
        assertThat(transaction.status()).hasToString(firstState);
    }

    @Test
    @DisplayName("un message del proveedor de más de 500 caracteres se recorta al tamaño de la columna")
    void longMessageIsTruncated() {
        Transaction transaction = Transaction.pending(COMMAND);

        transaction.markRejected("CODE", "x".repeat(Transaction.MESSAGE_MAX_LENGTH + 1));

        assertThat(transaction.message()).hasSize(Transaction.MESSAGE_MAX_LENGTH);
    }

    @Test
    @DisplayName("un code del proveedor de más de 100 caracteres se recorta al tamaño de la columna")
    void longCodeIsTruncated() {
        Transaction transaction = Transaction.pending(COMMAND);

        transaction.markRejected("C".repeat(Transaction.CODE_MAX_LENGTH + 1), "m");

        assertThat(transaction.code()).hasSize(Transaction.CODE_MAX_LENGTH);
    }

    static Stream<Arguments> executionColumnBounds() {
        return Stream.of(
                Arguments.of("t".repeat(100), "5500.00", true),
                Arguments.of("t".repeat(101), "5500.00", false),
                Arguments.of("txn-789", "99999999999999999.99", true),
                Arguments.of("txn-789", "100000000000000000.00", false),
                Arguments.of("txn-789", "5500.10", true),
                Arguments.of("txn-789", "5500.005", false),
                Arguments.of("txn-789", "-5500.00", true));
    }

    @ParameterizedTest(name = "id de {0} caracteres, balance {1} → {2}")
    @MethodSource("executionColumnBounds")
    @DisplayName("una aprobación cabe en las columnas solo con id ≤ 100 y balance dentro de NUMERIC(19,2)")
    void canRecordExecutionChecksColumnBounds(String providerTransactionId, String balance, boolean expected) {
        assertThat(Transaction.canRecordExecution(providerTransactionId, new BigDecimal(balance))).isEqualTo(expected);
    }

    @Test
    @DisplayName("markExecuted rechaza datos que no caben en las columnas y la transacción sigue PENDING")
    void markExecutedRejectsUnrecordableData() {
        Transaction transaction = Transaction.pending(COMMAND);

        assertThatThrownBy(() -> transaction.markExecuted("t".repeat(101), new BigDecimal("5500.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    @DisplayName("mismo payload con monto en otra escala (1500.0 vs 1500.00) se considera igual")
    void samePayloadComparesAmountByValue() {
        Transaction transaction = Transaction.pending(COMMAND);

        ExecuteTransactionCommand replay = new ExecuteTransactionCommand(
                "acc-123456", TransactionType.CREDIT, new BigDecimal("1500.0"), "MXN", "Transferencia recibida",
                "key-1");

        assertThat(transaction.hasSamePayloadAs(replay)).isTrue();
    }

    static Stream<Arguments> differentPayloads() {
        return Stream.of(
                Arguments.of("accountId", new ExecuteTransactionCommand("acc-999", TransactionType.CREDIT,
                        new BigDecimal("1500.00"), "MXN", "Transferencia recibida", "key-1")),
                Arguments.of("type", new ExecuteTransactionCommand("acc-123456", TransactionType.DEBIT,
                        new BigDecimal("1500.00"), "MXN", "Transferencia recibida", "key-1")),
                Arguments.of("amount", new ExecuteTransactionCommand("acc-123456", TransactionType.CREDIT,
                        new BigDecimal("1500.01"), "MXN", "Transferencia recibida", "key-1")),
                Arguments.of("currency", new ExecuteTransactionCommand("acc-123456", TransactionType.CREDIT,
                        new BigDecimal("1500.00"), "USD", "Transferencia recibida", "key-1")),
                Arguments.of("description", new ExecuteTransactionCommand("acc-123456", TransactionType.CREDIT,
                        new BigDecimal("1500.00"), "MXN", null, "key-1")));
    }

    @ParameterizedTest(name = "{0} distinto")
    @MethodSource("differentPayloads")
    @DisplayName("un payload que difiere en cualquier campo no es el mismo payload")
    void differentPayloadIsDetected(String field, ExecuteTransactionCommand other) {
        assertThat(Transaction.pending(COMMAND).hasSamePayloadAs(other)).isFalse();
    }

    @Test
    @DisplayName("la solicitud al proveedor lleva accountId, type, amount y currency de la transacción")
    void toProviderRequestCopiesPayload() {
        ProviderRequest request = Transaction.pending(COMMAND).toProviderRequest();

        assertThat(request).isEqualTo(
                new ProviderRequest("acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN"));
    }

    @FunctionalInterface
    interface TransitionWithError {
        void apply(Transaction transaction, String code, String message);
    }
}
