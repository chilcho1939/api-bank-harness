package mx.gilortiz.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

class TransactionServiceTest {

    private static final String KEY = "key-1";

    private final TransactionProvider provider = mock(TransactionProvider.class);
    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionService service = new TransactionService(new TransactionRules(), provider, repository);

    private final List<TransactionStatus> statusesAtInsert = new ArrayList<>();
    private final List<TransactionStatus> statusesAtProviderCall = new ArrayList<>();

    @BeforeEach
    void persistAndCallProviderRecordingStatus() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            statusesAtInsert.add(transaction.status());
            return transaction;
        });
        when(repository.completePending(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    static Stream<Arguments> ruleViolations() {
        return Stream.of(
                Arguments.of(command(TransactionType.CREDIT, "1.00", "MXN", null),
                        BusinessRuleViolation.AMOUNT_TOO_LOW),
                Arguments.of(command(TransactionType.DEBIT, "10000.01", "MXN", null),
                        BusinessRuleViolation.DEBIT_AMOUNT_EXCEEDS_LIMIT),
                Arguments.of(command(TransactionType.CREDIT, "1500.00", "USD", null),
                        BusinessRuleViolation.UNSUPPORTED_CURRENCY),
                Arguments.of(command(TransactionType.CREDIT, "1.00", "MXN", KEY),
                        BusinessRuleViolation.AMOUNT_TOO_LOW));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("ruleViolations")
    @DisplayName("RN04: si una regla falla, el proveedor no recibe la llamada y no se persiste nada")
    void ruleViolationDoesNotCallProvider(ExecuteTransactionCommand command, BusinessRuleViolation expected) {
        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.violation()).isEqualTo(expected));

        verifyNoInteractions(provider);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("la transacción se guarda PENDING antes de llamar al proveedor y el resultado se guarda después")
    void pendingIsPersistedBeforeProviderCall() {
        when(provider.execute(any())).thenAnswer(invocation -> {
            statusesAtProviderCall.addAll(statusesAtInsert);
            return new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), Instant.now());
        });

        service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", null));

        InOrder order = inOrder(repository, provider);
        order.verify(repository).saveAndFlush(any());
        order.verify(provider).execute(new ProviderRequest(
                "acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN"));
        order.verify(repository).completePending(any(), any(), any(), any(), any(), any());
        assertThat(statusesAtInsert).containsExactly(TransactionStatus.PENDING);
        assertThat(statusesAtProviderCall).containsExactly(TransactionStatus.PENDING);
    }

    @Test
    @DisplayName("RN05: aprobada se persiste EXECUTED con providerTransactionId y balanceAfter del proveedor")
    void approvedIsPersistedAsExecuted() {
        when(provider.execute(any()))
                .thenReturn(new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), Instant.now()));

        TransactionExecution execution = service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", null));
        Transaction result = execution.transaction();

        assertThat(execution.created()).isTrue();
        assertThat(result.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(result.providerTransactionId()).isEqualTo("txn-789");
        assertThat(result.balanceAfter()).isEqualByComparingTo("5500.00");
        verify(repository).completePending(null, TransactionStatus.EXECUTED, "txn-789", new BigDecimal("5500.00"),
                null, null);
        verify(provider).execute(any());
    }

    static Stream<Arguments> nonApprovedResults() {
        return Stream.of(
                Arguments.of(TransactionStatus.REJECTED, "INSUFFICIENT_FUNDS",
                        "The account does not have enough balance to complete the transaction"),
                Arguments.of(TransactionStatus.FAILED, "PROVIDER_UNAVAILABLE", "Proveedor no disponible"),
                Arguments.of(TransactionStatus.UNKNOWN, "PROVIDER_TIMEOUT", "Sin respuesta del proveedor"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonApprovedResults")
    @DisplayName("RN06: rechazo, falla o resultado incierto se persiste con su estado, code y message")
    void nonApprovedResultIsPersistedWithCodeAndMessage(TransactionStatus expected, String code, String message) {
        ProviderResult providerResult = switch (expected) {
            case REJECTED -> new ProviderResult.Rejected(code, message);
            case FAILED -> new ProviderResult.Failed(code, message);
            case UNKNOWN -> new ProviderResult.Unknown(code, message);
            default -> throw new IllegalArgumentException("estado sin resultado de error: " + expected);
        };
        when(provider.execute(any())).thenReturn(providerResult);

        TransactionExecution execution = service.execute(command(TransactionType.DEBIT, "10000.00", "MXN", null));
        Transaction result = execution.transaction();

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.message()).isEqualTo(message);
        assertThat(result.providerTransactionId()).isNull();
        assertThat(result.balanceAfter()).isNull();
        verify(repository).completePending(null, expected, null, null, code, message);
    }

    @Test
    @DisplayName("sin Idempotency-Key no se busca una transacción existente")
    void withoutKeyDoesNotLookUpExisting() {
        when(provider.execute(any()))
                .thenReturn(new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), Instant.now()));

        service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", null));

        verify(repository, never()).findByIdempotencyKey(any());
    }

    @Test
    @DisplayName("misma Idempotency-Key y mismo payload devuelve la transacción existente sin llamar al proveedor")
    void sameKeySamePayloadReturnsExisting() {
        Transaction existing = executed(command(TransactionType.CREDIT, "1500.00", "MXN", KEY));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        TransactionExecution execution = service.execute(command(TransactionType.CREDIT, "1500.0", "MXN", KEY));
        Transaction result = execution.transaction();

        assertThat(execution.created()).isFalse();
        assertThat(result).isSameAs(existing);
        assertThat(result.status()).isEqualTo(TransactionStatus.EXECUTED);
        verifyNoInteractions(provider);
        verify(repository, never()).saveAndFlush(any());
        verifyNoCompletion();
    }

    @Test
    @DisplayName("misma Idempotency-Key con payload distinto lanza conflicto sin llamar al proveedor")
    void sameKeyDifferentPayloadIsConflict() {
        Transaction existing = executed(command(TransactionType.CREDIT, "1500.00", "MXN", KEY));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.execute(command(TransactionType.CREDIT, "1600.00", "MXN", KEY)))
                .isInstanceOfSatisfying(IdempotencyKeyConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));

        verifyNoInteractions(provider);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("si el UNIQUE de la llave se viola en una carrera, se devuelve la transacción ganadora")
    void raceOnUniqueKeyReturnsWinner() {
        Transaction winner = Transaction.pending(command(TransactionType.CREDIT, "1500.00", "MXN", KEY));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DataIntegrityViolationException("uk_transactions_idempotency_key"))
                .when(repository).saveAndFlush(any());

        TransactionExecution execution = service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", KEY));
        Transaction result = execution.transaction();

        assertThat(execution.created()).isFalse();
        assertThat(result).isSameAs(winner);
        assertThat(result.status()).isEqualTo(TransactionStatus.PENDING);
        verifyNoInteractions(provider);
        verifyNoCompletion();
    }

    @Test
    @DisplayName("si la ganadora de la carrera tiene otro payload, se lanza conflicto")
    void raceWinnerWithDifferentPayloadIsConflict() {
        Transaction winner = Transaction.pending(command(TransactionType.DEBIT, "1500.00", "MXN", KEY));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DataIntegrityViolationException("uk_transactions_idempotency_key"))
                .when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", KEY)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("una violación de integridad sin ganadora que releer se propaga tal cual")
    void integrityViolationWithoutWinnerIsPropagated() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("otra restricción");
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        doThrow(violation).when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", KEY)))
                .isSameAs(violation);
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("una violación de integridad sin Idempotency-Key se propaga tal cual")
    void integrityViolationWithoutKeyIsPropagated() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("otra restricción");
        doThrow(violation).when(repository).saveAndFlush(any());

        assertThatThrownBy(() -> service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", null)))
                .isSameAs(violation);
        verifyNoInteractions(provider);
        verify(repository, never()).findByIdempotencyKey(any());
    }

    @Test
    @DisplayName("un monto con más de 2 decimales (1500.005) se rechaza antes de persistir o llamar al proveedor")
    void amountWithMoreThanTwoDecimalsIsRejected() {
        assertThatThrownBy(() -> service.execute(command(TransactionType.CREDIT, "1500.005", "MXN", KEY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 decimales");

        verifyNoInteractions(provider);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("al proveedor se le envía el monto normalizado a 2 decimales, igual al que se guarda")
    void providerReceivesNormalizedAmount() {
        when(provider.execute(any()))
                .thenReturn(new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), Instant.now()));

        TransactionExecution execution = service.execute(command(TransactionType.CREDIT, "1500", "MXN", null));
        Transaction result = execution.transaction();

        verify(provider).execute(argThat(request -> request.amount().compareTo(new BigDecimal("1500.00")) == 0
                && request.amount().scale() == 2));
        assertThat(result.amount()).isEqualByComparingTo("1500.00").hasScaleOf(2);
    }

    @Test
    @DisplayName("una Idempotency-Key en blanco equivale a no enviarla")
    void blankKeyIsTreatedAsAbsent() {
        when(provider.execute(any()))
                .thenReturn(new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), Instant.now()));

        TransactionExecution execution = service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", "   "));
        Transaction result = execution.transaction();

        assertThat(result.idempotencyKey()).isNull();
        verify(repository, never()).findByIdempotencyKey(any());
    }

    static Stream<Arguments> unrecordableApprovals() {
        return Stream.of(
                Arguments.of("transactionId de 101 caracteres", "t".repeat(101), "5500.00"),
                Arguments.of("balance con 18 dígitos enteros", "txn-789", "100000000000000000.00"),
                Arguments.of("balance con 3 decimales", "txn-789", "5500.005"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unrecordableApprovals")
    @DisplayName("una aprobación que no cabe en las columnas se guarda UNKNOWN con el transactionId para conciliar")
    void unrecordableApprovalIsPersistedAsUnknown(String scenario, String transactionId, String balance) {
        when(provider.execute(any()))
                .thenReturn(new ProviderResult.Approved(transactionId, new BigDecimal(balance), Instant.now()));

        TransactionExecution execution = service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", null));
        Transaction result = execution.transaction();

        assertThat(result.status()).isEqualTo(TransactionStatus.UNKNOWN);
        assertThat(result.code()).isEqualTo("PROVIDER_INVALID_RESPONSE");
        assertThat(result.message()).contains("transactionId=" + transactionId).contains("balance=" + balance);
        assertThat(result.providerTransactionId()).isNull();
        assertThat(result.balanceAfter()).isNull();
        verify(repository).completePending(any(), eq(TransactionStatus.UNKNOWN), any(), any(),
                eq("PROVIDER_INVALID_RESPONSE"), any());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("si falla el UPDATE final, la excepción se propaga y el resultado del proveedor queda en el log")
    void failedFinalUpdateIsLoggedAndPropagated(CapturedOutput output) {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("BD caída");
        when(provider.execute(any()))
                .thenReturn(new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), Instant.now()));
        when(repository.completePending(any(), any(), any(), any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.execute(command(TransactionType.CREDIT, "1500.00", "MXN", null)))
                .isSameAs(failure);

        assertThat(output).contains("ERROR").contains("falló el UPDATE")
                .contains("status=EXECUTED").contains("providerTransactionId=txn-789");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("si el UPDATE no encuentra la fila PENDING, se lanza conflicto de estado y se registra el resultado")
    void finalUpdateWithoutPendingRowIsStateConflict(CapturedOutput output) {
        when(provider.execute(any())).thenReturn(new ProviderResult.Rejected("INSUFFICIENT_FUNDS", "Sin saldo"));
        when(repository.completePending(any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.execute(command(TransactionType.DEBIT, "1500.00", "MXN", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya no está PENDING");

        assertThat(output).contains("ERROR").contains("status=REJECTED").contains("code=INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("RN07: la consulta pasa los filtros al repositorio con página, límite y orden createdAt desc, id desc")
    void searchUsesStableOrderAndRequestedPage() {
        Page<Transaction> page = new PageImpl<>(List.of());
        when(repository.findAll(ArgumentMatchers.<Specification<Transaction>>any(), any(Pageable.class)))
                .thenReturn(page);

        Page<Transaction> result = service.search(
                new TransactionSearchCriteria("acc-123456", TransactionStatus.EXECUTED, TransactionType.DEBIT), 2, 25);

        assertThat(result).isSameAs(page);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(ArgumentMatchers.<Specification<Transaction>>any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        verifyNoInteractions(provider);
    }

    private void verifyNoCompletion() {
        verify(repository, never()).completePending(any(), any(), any(), any(), any(), any());
    }

    private static ExecuteTransactionCommand command(TransactionType type, String amount, String currency,
                                                     String idempotencyKey) {
        return new ExecuteTransactionCommand("acc-123456", type, new BigDecimal(amount), currency,
                "Transferencia recibida", idempotencyKey);
    }

    private static Transaction executed(ExecuteTransactionCommand command) {
        Transaction transaction = Transaction.pending(command);
        transaction.markExecuted("txn-789", new BigDecimal("5500.00"));
        return transaction;
    }
}
