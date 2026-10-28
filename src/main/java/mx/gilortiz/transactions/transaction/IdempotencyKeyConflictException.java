package mx.gilortiz.transactions.transaction;

/**
 * La {@code Idempotency-Key} ya se usó con un payload distinto: es un error del cliente, no una repetición.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public static final String CODE = "IDEMPOTENCY_KEY_CONFLICT";

    public IdempotencyKeyConflictException() {
        super("La Idempotency-Key ya se usó con un payload distinto");
    }

    public String code() {
        return CODE;
    }
}
