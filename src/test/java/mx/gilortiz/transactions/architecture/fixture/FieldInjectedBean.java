package mx.gilortiz.transactions.architecture.fixture;

import org.springframework.beans.factory.annotation.Autowired;

/** Fixture que viola a propósito la regla de inyección por constructor. */
public class FieldInjectedBean {

    @Autowired
    private Object collaborator;
}
