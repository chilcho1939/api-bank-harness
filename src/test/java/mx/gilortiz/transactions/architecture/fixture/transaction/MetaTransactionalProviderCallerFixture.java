package mx.gilortiz.transactions.architecture.fixture.transaction;

import mx.gilortiz.transactions.transaction.ProviderRequest;
import mx.gilortiz.transactions.transaction.ProviderResult;
import mx.gilortiz.transactions.transaction.TransactionProvider;

/** Fixture que viola a propósito la regla mediante una meta-anotación de {@code @Transactional} en la clase. */
@TransactionalOperation
public class MetaTransactionalProviderCallerFixture {

    private final TransactionProvider provider;

    public MetaTransactionalProviderCallerFixture(TransactionProvider provider) {
        this.provider = provider;
    }

    public ProviderResult execute(ProviderRequest request) {
        return provider.execute(request);
    }
}
