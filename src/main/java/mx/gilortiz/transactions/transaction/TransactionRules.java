package mx.gilortiz.transactions.transaction;

import java.math.BigDecimal;
import java.util.Objects;

final class TransactionRules {

    private static final BigDecimal MINIMUM_AMOUNT_EXCLUSIVE = new BigDecimal("1.00");
    private static final BigDecimal MAXIMUM_DEBIT_AMOUNT = new BigDecimal("10000.00");
    private static final String SUPPORTED_CURRENCY = "MXN";

    /**
     * Valida RN01–RN03 en ese orden y lanza la primera regla que falla.
     */
    void validate(TransactionType type, BigDecimal amount, String currency) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        if (amount.compareTo(MINIMUM_AMOUNT_EXCLUSIVE) <= 0) {
            throw new BusinessRuleException(BusinessRuleViolation.AMOUNT_TOO_LOW);
        }
        if (type == TransactionType.DEBIT && amount.compareTo(MAXIMUM_DEBIT_AMOUNT) > 0) {
            throw new BusinessRuleException(BusinessRuleViolation.DEBIT_AMOUNT_EXCEEDS_LIMIT);
        }
        if (!SUPPORTED_CURRENCY.equals(currency)) {
            throw new BusinessRuleException(BusinessRuleViolation.UNSUPPORTED_CURRENCY);
        }
    }
}
