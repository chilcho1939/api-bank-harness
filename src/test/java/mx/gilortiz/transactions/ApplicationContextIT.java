package mx.gilortiz.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class ApplicationContextIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final Flyway flyway;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationContextIT(Flyway flyway, JdbcTemplate jdbcTemplate) {
        this.flyway = flyway;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @DisplayName("el contexto arranca contra Postgres real con la migración V1 aplicada")
    void contextStartsWithV1Applied() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
    }

    @Test
    @DisplayName("idempotency_key tiene constraint UNIQUE en la tabla transactions")
    void idempotencyKeyIsUnique() {
        List<String> uniqueColumns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name AND tc.table_name = kcu.table_name
                WHERE tc.table_name = 'transactions' AND tc.constraint_type = 'UNIQUE'
                """, String.class);

        assertThat(uniqueColumns).containsExactly("idempotency_key");
    }

    @Test
    @DisplayName("existen índices para los filtros de RN07: account_id, status y type")
    void filterIndexesExist() {
        List<String> indexDefinitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'transactions'", String.class);

        assertThat(indexDefinitions)
                .anyMatch(def -> def.contains("(account_id, created_at, id)"))
                .anyMatch(def -> def.contains("(status, created_at, id)"))
                .anyMatch(def -> def.contains("(type, created_at, id)"))
                .anyMatch(def -> def.contains("(created_at, id)"));
    }
}
