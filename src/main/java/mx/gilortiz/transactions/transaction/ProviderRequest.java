package mx.gilortiz.transactions.transaction;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Datos que el proveedor necesita para ejecutar la transacción.
 */
public record ProviderRequest(String accountId, TransactionType type, BigDecimal amount, String currency) {

    public ProviderRequest {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }
}
