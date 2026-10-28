package mx.gilortiz.transactions.architecture.fixture.transaction;

import mx.gilortiz.transactions.transaction.ProviderRequest;
import mx.gilortiz.transactions.transaction.ProviderResult;
import mx.gilortiz.transactions.transaction.TransactionProvider;

/** Fixture: llamador directo del puerto, sin {@code @Transactional} (cumple la regla por sí solo). */
public class ProviderCallerFixture {

    private final TransactionProvider provider;

    public ProviderCallerFixture(TransactionProvider provider) {
        this.provider = provider;
    }

    public ProviderResult execute(ProviderRequest request) {
        return provider.execute(request);
    }
}
