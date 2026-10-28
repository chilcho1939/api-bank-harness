package mx.gilortiz.transactions.transaction;

/**
 * Reglas de negocio RN01–RN03. El nombre de cada constante es el código estable que recibe el cliente.
 */
public enum BusinessRuleViolation {
    AMOUNT_TOO_LOW("El monto debe ser mayor a 1.00"),
    DEBIT_AMOUNT_EXCEEDS_LIMIT("El monto de un DEBIT no puede ser mayor a 10,000.00"),
    UNSUPPORTED_CURRENCY("La moneda debe ser MXN");

    private final String message;

    BusinessRuleViolation(String message) {
        this.message = message;
    }

    public String code() {
        return name();
    }

    public String message() {
        return message;
    }
}
