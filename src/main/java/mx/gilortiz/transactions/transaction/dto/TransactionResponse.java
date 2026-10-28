package mx.gilortiz.transactions.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import mx.gilortiz.transactions.transaction.Transaction;
import mx.gilortiz.transactions.transaction.TransactionStatus;
import mx.gilortiz.transactions.transaction.TransactionType;

/**
 * Transacción tal como la expone la API; los campos son los del response de la spec.
 */
public record TransactionResponse(
        UUID id,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String description,
        TransactionStatus status,
        String providerTransactionId,
        BigDecimal balanceAfter,
        Instant createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(transaction.id(), transaction.accountId(), transaction.type(),
                transaction.amount(), transaction.currency(), transaction.description(), transaction.status(),
                transaction.providerTransactionId(), transaction.balanceAfter(), transaction.createdAt());
    }
}
