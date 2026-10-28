package mx.gilortiz.transactions.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Solicitud de ejecución ya validada en formato.
 *
 * @param amount         se normaliza a 2 decimales, la escala de la columna; más decimales se rechazan
 * @param description    opcional
 * @param idempotencyKey opcional (en blanco equivale a ausente); con valor, una repetición con el mismo payload
 *                       devuelve la transacción existente
 */
public record ExecuteTransactionCommand(String accountId, TransactionType type, BigDecimal amount, String currency,
                                        String description, String idempotencyKey) {

    static final int AMOUNT_SCALE = 2;

    public ExecuteTransactionCommand {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = normalizeScale(amount);
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            idempotencyKey = null;
        }
    }

    // Lo que se envía al proveedor debe ser exactamente lo que guarda NUMERIC(19,2): sin redondeos silenciosos.
    private static BigDecimal normalizeScale(BigDecimal amount) {
        try {
            return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("El monto admite como máximo " + AMOUNT_SCALE + " decimales", e);
        }
    }
}
