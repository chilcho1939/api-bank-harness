package mx.gilortiz.transactions.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Transacción ejecutada contra el proveedor. Nace {@code PENDING} y cambia de estado una sola vez
 * con los métodos {@code mark*}; no expone setters.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    static final int MESSAGE_MAX_LENGTH = 500;
    static final int CODE_MAX_LENGTH = 100;
    static final int PROVIDER_TRANSACTION_ID_MAX_LENGTH = 100;
    private static final int BALANCE_SCALE = 2;
    private static final int BALANCE_MAX_INTEGER_DIGITS = 17;

    // UUID v7 generado en la app al persistir: ordenable por tiempo y sin ida y vuelta a la BD.
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    private String code;

    private String message;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transaction() {
    }

    private Transaction(ExecuteTransactionCommand command, Instant createdAt) {
        this.accountId = command.accountId();
        this.type = command.type();
        this.amount = command.amount();
        this.currency = command.currency();
        this.description = command.description();
        this.idempotencyKey = command.idempotencyKey();
        this.status = TransactionStatus.PENDING;
        this.createdAt = createdAt;
    }

    /**
     * Crea la transacción en {@code PENDING}, antes de llamar al proveedor.
     */
    static Transaction pending(ExecuteTransactionCommand command) {
        Objects.requireNonNull(command, "command");
        // Postgres guarda microsegundos: truncar evita que la respuesta difiera de lo que se lee después.
        return new Transaction(command, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Indica si el identificador y el saldo del proveedor caben en sus columnas ({@code VARCHAR(100)},
     * {@code NUMERIC(19,2)}) sin recortarse ni redondearse.
     */
    static boolean canRecordExecution(String providerTransactionId, BigDecimal balanceAfter) {
        BigDecimal balance = balanceAfter.stripTrailingZeros();
        return providerTransactionId.length() <= PROVIDER_TRANSACTION_ID_MAX_LENGTH
                && balance.scale() <= BALANCE_SCALE
                && balance.precision() - balance.scale() <= BALANCE_MAX_INTEGER_DIGITS;
    }

    /** RN05: el proveedor aprobó; se guardan su identificador y el saldo resultante. */
    void markExecuted(String providerTransactionId, BigDecimal balanceAfter) {
        Objects.requireNonNull(providerTransactionId, "providerTransactionId");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        if (!canRecordExecution(providerTransactionId, balanceAfter)) {
            throw new IllegalArgumentException("El resultado aprobado no cabe en las columnas de la transacción");
        }
        transitionTo(TransactionStatus.EXECUTED);
        this.providerTransactionId = providerTransactionId;
        this.balanceAfter = balanceAfter;
    }

    /** RN06: el proveedor rechazó; se guardan su {@code code} y {@code message}. */
    void markRejected(String code, String message) {
        finishWithError(TransactionStatus.REJECTED, code, message);
    }

    /** La transacción no se ejecutó (proveedor no disponible o error propio del proveedor). */
    void markFailed(String code, String message) {
        finishWithError(TransactionStatus.FAILED, code, message);
    }

    /** No se sabe si el proveedor aplicó la transacción; queda para conciliación. */
    void markUnknown(String code, String message) {
        finishWithError(TransactionStatus.UNKNOWN, code, message);
    }

    /**
     * Compara contra los datos de una nueva solicitud con la misma {@code Idempotency-Key}.
     * El monto se compara por valor ({@code 1500.0} equivale a {@code 1500.00}).
     */
    boolean hasSamePayloadAs(ExecuteTransactionCommand command) {
        return accountId.equals(command.accountId())
                && type == command.type()
                && amount.compareTo(command.amount()) == 0
                && currency.equals(command.currency())
                && Objects.equals(description, command.description());
    }

    ProviderRequest toProviderRequest() {
        return new ProviderRequest(accountId, type, amount, currency);
    }

    private void finishWithError(TransactionStatus finalStatus, String code, String message) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        transitionTo(finalStatus);
        this.code = truncate(code, CODE_MAX_LENGTH);
        this.message = truncate(message, MESSAGE_MAX_LENGTH);
    }

    private void transitionTo(TransactionStatus finalStatus) {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "La transacción " + id + " ya está en " + status + "; no puede pasar a " + finalStatus);
        }
        this.status = finalStatus;
    }

    // code y message los redacta un tercero: recortarlos evita que un texto largo impida guardar el resultado.
    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public UUID id() {
        return id;
    }

    public String accountId() {
        return accountId;
    }

    public TransactionType type() {
        return type;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public String description() {
        return description;
    }

    public TransactionStatus status() {
        return status;
    }

    public String providerTransactionId() {
        return providerTransactionId;
    }

    public BigDecimal balanceAfter() {
        return balanceAfter;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
