package mx.gilortiz.transactions.architecture.fixture.transaction;

import mx.gilortiz.transactions.architecture.fixture.provider.http.HttpAdapterFixture;

/** Fixture que viola a propósito la regla: el dominio usa el adaptador HTTP. */
public class DomainDependingOnHttpFixture {

    private final HttpAdapterFixture adapter = new HttpAdapterFixture();
}
