package mx.gilortiz.transactions.transaction;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Caso de uso de {@code POST /transactions}:
 * reglas → idempotencia → {@code PENDING} → proveedor → estado final.
 * <p>
 * Sin {@code @Transactional} a propósito: el INSERT y el UPDATE corren cada uno en la transacción corta del
 * repositorio, y la llamada al proveedor ocurre entre ambos sin retener una conexión de BD.
 * <p>
 * Si el UPDATE final falla, la fila queda {@code PENDING} y el resultado del proveedor se registra en el log
 * para conciliarlo.
 */
@Service
class TransactionService {

    static final String PROVIDER_INVALID_RESPONSE = "PROVIDER_INVALID_RESPONSE";

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRules rules;
    private final TransactionProvider provider;
    private final TransactionRepository repository;

    TransactionService(TransactionRules rules, TransactionProvider provider, TransactionRepository repository) {
        this.rules = rules;
        this.provider = provider;
        this.repository = repository;
    }

    /**
     * @throws BusinessRuleException           si falla RN01–RN03; el proveedor no recibe la llamada (RN04)
     * @throws IdempotencyKeyConflictException si la llave ya existe con un payload distinto
     */
    Transaction execute(ExecuteTransactionCommand command) {
        rules.validate(command.type(), command.amount(), command.currency());

        Optional<Transaction> existing = findByIdempotencyKey(command);
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        Transaction transaction;
        try {
            transaction = repository.saveAndFlush(Transaction.pending(command));
        } catch (DataIntegrityViolationException e) {
            return replayWinnerOfRace(command, e);
        }

        ProviderResult result = provider.execute(transaction.toProviderRequest());
        applyResult(transaction, result);
        recordOutcome(transaction);
        return transaction;
    }

    private Optional<Transaction> findByIdempotencyKey(ExecuteTransactionCommand command) {
        if (command.idempotencyKey() == null) {
            return Optional.empty();
        }
        return repository.findByIdempotencyKey(command.idempotencyKey());
    }

    // Otra solicitud con la misma llave insertó primero: el UNIQUE de la BD decide y se devuelve la ganadora.
    private Transaction replayWinnerOfRace(ExecuteTransactionCommand command, DataIntegrityViolationException e) {
        Transaction winner = findByIdempotencyKey(command).orElseThrow(() -> e);
        log.info("Carrera de Idempotency-Key resuelta a favor de la transacción {}", winner.id());
        return replay(winner, command);
    }

    private static Transaction replay(Transaction existing, ExecuteTransactionCommand command) {
        if (!existing.hasSamePayloadAs(command)) {
            throw new IdempotencyKeyConflictException();
        }
        return existing;
    }

    private void recordOutcome(Transaction transaction) {
        int updated;
        try {
            updated = repository.completePending(transaction.id(), transaction.status(),
                    transaction.providerTransactionId(), transaction.balanceAfter(), transaction.code(),
                    transaction.message());
        } catch (RuntimeException e) {
            logUnrecordedOutcome(transaction, "falló el UPDATE");
            throw e;
        }
        if (updated != 1) {
            logUnrecordedOutcome(transaction, "la fila no existe o ya no está PENDING");
            throw new IllegalStateException("La transacción " + transaction.id() + " no existe o ya no está PENDING");
        }
    }

    private static void logUnrecordedOutcome(Transaction transaction, String reason) {
        log.error("Resultado del proveedor sin guardar ({}); conciliar la transacción {}: status={}, "
                        + "providerTransactionId={}, code={}", reason, transaction.id(), transaction.status(),
                transaction.providerTransactionId(), transaction.code());
    }

    private static void applyResult(Transaction transaction, ProviderResult result) {
        switch (result) {
            case ProviderResult.Approved approved
                    when Transaction.canRecordExecution(approved.transactionId(), approved.balance()) ->
                    transaction.markExecuted(approved.transactionId(), approved.balance());
            // Aprobado pero imposible de guardar tal cual: se registra como incierto, con el id para conciliar.
            case ProviderResult.Approved approved -> {
                log.warn("Respuesta aprobada del proveedor fuera de rango para la transacción {}: transactionId={}",
                        transaction.id(), approved.transactionId());
                transaction.markUnknown(PROVIDER_INVALID_RESPONSE,
                        "Aprobada con datos fuera de rango; transactionId=" + approved.transactionId()
                                + ", balance=" + approved.balance().toPlainString());
            }
            case ProviderResult.Rejected rejected -> transaction.markRejected(rejected.code(), rejected.message());
            case ProviderResult.Failed failed -> transaction.markFailed(failed.code(), failed.message());
            case ProviderResult.Unknown unknown -> transaction.markUnknown(unknown.code(), unknown.message());
        }
    }
}
