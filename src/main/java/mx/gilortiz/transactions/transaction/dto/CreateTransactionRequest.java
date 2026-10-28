package mx.gilortiz.transactions.transaction.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import mx.gilortiz.transactions.transaction.ExecuteTransactionCommand;
import mx.gilortiz.transactions.transaction.TransactionType;

/**
 * Cuerpo de {@code POST /transactions}. Aquí solo se valida el formato (400); los límites de monto y la moneda
 * son reglas de negocio RN01–RN03 (422) y se evalúan en el servicio. Por eso {@code amount} no lleva
 * {@code @Positive} ni {@code currency} un tamaño: {@code -5} o {@code USDX} son violaciones de RN01/RN03.
 *
 * @param amount a lo más 17 enteros y 2 decimales, lo que cabe en {@code NUMERIC(19,2)}
 */
public record CreateTransactionRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 100, message = "admite como máximo {max} caracteres")
        String accountId,

        @NotNull(message = "es obligatorio")
        TransactionType type,

        @NotNull(message = "es obligatorio")
        @Digits(integer = 17, fraction = 2, message = "admite como máximo {integer} enteros y {fraction} decimales")
        BigDecimal amount,

        @NotBlank(message = "es obligatorio")
        String currency,

        @Size(max = 255, message = "admite como máximo {max} caracteres")
        String description) {

    public ExecuteTransactionCommand toCommand(String idempotencyKey) {
        return new ExecuteTransactionCommand(accountId, type, amount, currency, description, idempotencyKey);
    }
}
