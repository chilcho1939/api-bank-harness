package mx.gilortiz.transactions.transaction;

/**
 * Puerto hacia el proveedor externo que ejecuta la transacción.
 * <p>
 * No lanza excepciones: todo desenlace (incluidas las fallas técnicas) llega como un {@link ProviderResult}.
 */
public interface TransactionProvider {

    ProviderResult execute(ProviderRequest request);
}
