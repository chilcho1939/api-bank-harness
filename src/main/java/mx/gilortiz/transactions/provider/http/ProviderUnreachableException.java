package mx.gilortiz.transactions.provider.http;

/**
 * La petición no llegó al proveedor (conexión rechazada, timeout de conexión, host desconocido).
 * Es la única falla que se reintenta: reenviar no puede duplicar la operación.
 */
final class ProviderUnreachableException extends RuntimeException {

    ProviderUnreachableException(Throwable cause) {
        super("No se pudo conectar con el proveedor", cause);
    }
}
