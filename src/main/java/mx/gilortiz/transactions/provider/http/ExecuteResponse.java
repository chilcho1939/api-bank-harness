package mx.gilortiz.transactions.provider.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cuerpo de respuesta del proveedor. Une el de éxito ({@code transactionId}, {@code balance}, {@code executedAt})
 * y el de falla ({@code code}, {@code message}); {@code status} viene en ambos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ExecuteResponse(
        String transactionId,
        String status,
        BigDecimal balance,
        Instant executedAt,
        String code,
        String message) {

    static final String APPROVED = "APPROVED";

    boolean isApproved() {
        return APPROVED.equals(status) && transactionId != null && balance != null && executedAt != null;
    }

    boolean hasError() {
        return code != null && message != null;
    }
}
