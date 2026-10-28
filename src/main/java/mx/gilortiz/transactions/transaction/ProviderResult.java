package mx.gilortiz.transactions.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Desenlace de la llamada al proveedor. El servicio lo procesa con un {@code switch} exhaustivo.
 */
public sealed interface ProviderResult {

    /** El proveedor ejecutó la transacción (RN05). */
    record Approved(String transactionId, BigDecimal balance, Instant executedAt) implements ProviderResult {

        public Approved {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(balance, "balance");
        }
    }

    /** El proveedor respondió y rechazó la transacción por una regla propia (RN06). */
    record Rejected(String code, String message) implements ProviderResult {

        public Rejected {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /** La transacción no se ejecutó: el proveedor no estaba disponible o respondió con un error propio. */
    record Failed(String code, String message) implements ProviderResult {

        public Failed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /** No se sabe si el proveedor aplicó la transacción (p. ej. timeout de lectura); requiere conciliación. */
    record Unknown(String code, String message) implements ProviderResult {

        public Unknown {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
