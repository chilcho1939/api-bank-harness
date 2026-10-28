package mx.gilortiz.transactions.common.web;

/**
 * Error de formato de un campo, parámetro o header, dentro de la lista {@code errors} de un 400.
 */
public record InvalidField(String field, String message) {
}
