package mx.gilortiz.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionRulesTest {

    private static final String MXN = "MXN";

    private final TransactionRules rules = new TransactionRules();

    @ParameterizedTest(name = "un {0} de {1} se rechaza con AMOUNT_TOO_LOW")
    @DisplayName("RN01: un monto menor o igual a 1.00 se rechaza para CREDIT y DEBIT")
    @CsvSource({
            "CREDIT, 1.00",
            "DEBIT,  1.00",
            "CREDIT, 1",
            "DEBIT,  1.0",
            "CREDIT, 0.99",
            "DEBIT,  0.00",
            "CREDIT, -5.00"
    })
    void rejectsAmountNotGreaterThanMinimum(TransactionType type, BigDecimal amount) {
        assertRejected(type, amount, MXN, BusinessRuleViolation.AMOUNT_TOO_LOW);
    }

    @ParameterizedTest(name = "un {0} de 1.01 se acepta")
    @DisplayName("RN01: un monto de 1.01 se acepta para CREDIT y DEBIT")
    @EnumSource(TransactionType.class)
    void acceptsAmountJustAboveMinimum(TransactionType type) {
        assertAccepted(type, new BigDecimal("1.01"), MXN);
    }

    @ParameterizedTest(name = "un DEBIT de {0} se acepta")
    @DisplayName("RN02: un DEBIT hasta 10,000.00 inclusive se acepta")
    @ValueSource(strings = {"10000.00", "10000", "9999.99"})
    void acceptsDebitUpToLimit(String amount) {
        assertAccepted(TransactionType.DEBIT, new BigDecimal(amount), MXN);
    }

    @ParameterizedTest(name = "un DEBIT de {0} se rechaza con DEBIT_AMOUNT_EXCEEDS_LIMIT")
    @DisplayName("RN02: un DEBIT mayor a 10,000.00 se rechaza")
    @ValueSource(strings = {"10000.01", "10000.001", "50000.00"})
    void rejectsDebitAboveLimit(String amount) {
        assertRejected(TransactionType.DEBIT, new BigDecimal(amount), MXN,
                BusinessRuleViolation.DEBIT_AMOUNT_EXCEEDS_LIMIT);
    }

    @ParameterizedTest(name = "un CREDIT de {0} se acepta")
    @DisplayName("RN02: un CREDIT no tiene límite máximo")
    @ValueSource(strings = {"10000.00", "10000.01", "1000000.00"})
    void acceptsCreditWithoutUpperLimit(String amount) {
        assertAccepted(TransactionType.CREDIT, new BigDecimal(amount), MXN);
    }

    @ParameterizedTest(name = "un {0} en MXN se acepta")
    @DisplayName("RN03: la moneda MXN se acepta para CREDIT y DEBIT")
    @EnumSource(TransactionType.class)
    void acceptsMxn(TransactionType type) {
        assertAccepted(type, new BigDecimal("1500.00"), MXN);
    }

    @ParameterizedTest(name = "la moneda \"{0}\" se rechaza con UNSUPPORTED_CURRENCY")
    @DisplayName("RN03: una moneda distinta de MXN se rechaza")
    @ValueSource(strings = {"USD", "EUR", "mxn", "MX", "MXNN", ""})
    void rejectsCurrencyOtherThanMxn(String currency) {
        assertRejected(TransactionType.CREDIT, new BigDecimal("1500.00"), currency,
                BusinessRuleViolation.UNSUPPORTED_CURRENCY);
    }

    @Test
    @DisplayName("si varias reglas fallan se reporta la primera en orden RN01, RN02, RN03")
    void reportsFirstViolationInRuleOrder() {
        assertRejected(TransactionType.DEBIT, new BigDecimal("1.00"), "USD", BusinessRuleViolation.AMOUNT_TOO_LOW);
        assertRejected(TransactionType.DEBIT, new BigDecimal("10000.01"), "USD",
                BusinessRuleViolation.DEBIT_AMOUNT_EXCEEDS_LIMIT);
    }

    @ParameterizedTest(name = "{0} expone el código {1}")
    @DisplayName("cada rechazo lleva un código estable en inglés y un mensaje descriptivo")
    @CsvSource(delimiter = '|', value = {
            "AMOUNT_TOO_LOW             | AMOUNT_TOO_LOW             | El monto debe ser mayor a 1.00",
            "DEBIT_AMOUNT_EXCEEDS_LIMIT | DEBIT_AMOUNT_EXCEEDS_LIMIT | El monto de un DEBIT no puede ser mayor a 10,000.00",
            "UNSUPPORTED_CURRENCY       | UNSUPPORTED_CURRENCY       | La moneda debe ser MXN"
    })
    void violationExposesStableCodeAndMessage(BusinessRuleViolation violation, String code, String message) {
        BusinessRuleException exception = new BusinessRuleException(violation);

        assertThat(exception.code()).isEqualTo(code);
        assertThat(exception.getMessage()).isEqualTo(message);
        assertThat(exception.violation()).isEqualTo(violation);
    }

    private void assertAccepted(TransactionType type, BigDecimal amount, String currency) {
        assertThatCode(() -> rules.validate(type, amount, currency)).doesNotThrowAnyException();
    }

    private void assertRejected(TransactionType type, BigDecimal amount, String currency,
                                BusinessRuleViolation expected) {
        assertThatThrownBy(() -> rules.validate(type, amount, currency))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception -> {
                    assertThat(exception.violation()).isEqualTo(expected);
                    assertThat(exception.code()).isEqualTo(expected.name());
                    assertThat(exception.getMessage()).isEqualTo(expected.message());
                });
    }
}
