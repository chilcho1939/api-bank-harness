package mx.gilortiz.transactions.transaction;

/**
 * Ciclo de vida de una transacción: nace {@code PENDING} y pasa una sola vez a un estado final.
 */
public enum TransactionStatus {
    PENDING,
    EXECUTED,
    REJECTED,
    FAILED,
    UNKNOWN
}
