package mx.gilortiz.transactions.transaction;

import java.util.Objects;

/**
 * Resultado de {@code POST /transactions}.
 *
 * @param created {@code true} si esta solicitud creó la transacción; {@code false} si es la repetición de una
 *                {@code Idempotency-Key} ya registrada (la transacción devuelta puede seguir {@code PENDING} si la
 *                solicitud original aún espera al proveedor)
 */
record TransactionExecution(Transaction transaction, boolean created) {

    TransactionExecution {
        Objects.requireNonNull(transaction, "transaction");
    }
}
