package mx.gilortiz.transactions.architecture.fixture.transaction;

import mx.gilortiz.transactions.transaction.ProviderRequest;
import mx.gilortiz.transactions.transaction.ProviderResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixture que viola a propósito la regla de forma indirecta: no conoce el puerto, pero abre una transacción
 * alrededor de un llamador que sí lo usa (p. ej. un controller transaccional que invoca al servicio).
 */
public class TransactionalIndirectCallerFixture {

    private final ProviderCallerFixture caller;

    public TransactionalIndirectCallerFixture(ProviderCallerFixture caller) {
        this.caller = caller;
    }

    @Transactional
    public ProviderResult execute(ProviderRequest request) {
        return caller.execute(request);
    }
}
