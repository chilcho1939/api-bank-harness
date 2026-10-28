package mx.gilortiz.transactions;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Limpieza única de la BD compartida entre los {@code *IT} (ver {@link PostgresTestConfiguration}).
 */
public final class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;

    DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clean() {
        jdbcTemplate.update("DELETE FROM transactions");
    }
}
