package mx.gilortiz.transactions.transaction;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Guarda el estado final en una transacción corta y solo si la fila sigue {@code PENDING}: la BD también
     * garantiza la transición única.
     *
     * @return filas actualizadas: 1, o 0 si la transacción no existe o ya salió de {@code PENDING}
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE Transaction t
            SET t.status = :status, t.providerTransactionId = :providerTransactionId,
                t.balanceAfter = :balanceAfter, t.code = :code, t.message = :message
            WHERE t.id = :id AND t.status = mx.gilortiz.transactions.transaction.TransactionStatus.PENDING
            """)
    int completePending(@Param("id") UUID id, @Param("status") TransactionStatus status,
                        @Param("providerTransactionId") String providerTransactionId,
                        @Param("balanceAfter") BigDecimal balanceAfter, @Param("code") String code,
                        @Param("message") String message);
}
