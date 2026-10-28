package mx.gilortiz.transactions.architecture.fixture.transaction;

import mx.gilortiz.transactions.transaction.ProviderRequest;
import mx.gilortiz.transactions.transaction.ProviderResult;
import mx.gilortiz.transactions.transaction.TransactionProvider;
import org.springframework.transaction.annotation.Transactional;

/** Fixture que viola a propósito la regla: abre una transacción de BD alrededor de la llamada al proveedor. */
public class TransactionalProviderCallerFixture {

    private final TransactionProvider provider;

    public TransactionalProviderCallerFixture(TransactionProvider provider) {
        this.provider = provider;
    }

    @Transactional
    public ProviderResult execute(ProviderRequest request) {
        return provider.execute(request);
    }
}
