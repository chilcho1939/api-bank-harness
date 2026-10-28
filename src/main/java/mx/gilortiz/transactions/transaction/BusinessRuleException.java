package mx.gilortiz.transactions.transaction;

public class BusinessRuleException extends RuntimeException {

    private final BusinessRuleViolation violation;

    public BusinessRuleException(BusinessRuleViolation violation) {
        super(violation.message());
        this.violation = violation;
    }

    public BusinessRuleViolation violation() {
        return violation;
    }

    public String code() {
        return violation.code();
    }
}
