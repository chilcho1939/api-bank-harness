package mx.gilortiz.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExecuteTransactionCommandTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1500", "1500.0", "1500.00", "1500.000", "1.5E+3"})
    @DisplayName("un monto con hasta 2 decimales significativos se normaliza a escala 2 sin cambiar su valor")
    void amountIsNormalizedToScaleTwo(String amount) {
        ExecuteTransactionCommand command = command(amount, "key-1");

        assertThat(command.amount()).isEqualByComparingTo("1500.00").hasScaleOf(2);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1500.005", "1.001", "10000.001"})
    @DisplayName("un monto con más de 2 decimales se rechaza en lugar de redondearse")
    void amountWithMoreThanTwoDecimalsIsRejected(String amount) {
        assertThatThrownBy(() -> command(amount, "key-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El monto admite como máximo 2 decimales");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("una Idempotency-Key ausente o en blanco queda en null")
    void blankIdempotencyKeyBecomesNull(String idempotencyKey) {
        assertThat(command("1500.00", idempotencyKey).idempotencyKey()).isNull();
    }

    private static ExecuteTransactionCommand command(String amount, String idempotencyKey) {
        return new ExecuteTransactionCommand("acc-123456", TransactionType.CREDIT, new BigDecimal(amount), "MXN",
                "Transferencia recibida", idempotencyKey);
    }
}
