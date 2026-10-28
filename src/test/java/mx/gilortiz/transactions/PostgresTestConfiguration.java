package mx.gilortiz.transactions;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres real compartido por todos los {@code *IT}: un solo contenedor por JVM, aunque haya varios contextos
 * de Spring (cada uno con sus propiedades). Como la BD es compartida, los {@code *IT} que escriben limpian con
 * {@link DatabaseCleaner} antes de cada test.
 * <p>
 * Se expone como {@link JdbcConnectionDetails} y no como bean del contenedor: Spring cierra los contenedores que
 * son beans al cerrar su contexto, y eso detendría el que siguen usando los demás. Ryuk lo elimina al terminar.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    private static PostgreSQLContainer postgres;

    @Bean
    JdbcConnectionDetails postgresConnectionDetails() {
        PostgreSQLContainer container = sharedPostgres();
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return container.getUsername();
            }

            @Override
            public String getPassword() {
                return container.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return container.getJdbcUrl();
            }
        };
    }

    @Bean
    DatabaseCleaner databaseCleaner(JdbcTemplate jdbcTemplate) {
        return new DatabaseCleaner(jdbcTemplate);
    }

    // Arranque perezoso, no en un inicializador estático: si Docker falla, cada IT reporta el error real de
    // Testcontainers en vez de un ExceptionInInitializerError/NoClassDefFoundError.
    private static synchronized PostgreSQLContainer sharedPostgres() {
        if (postgres == null) {
            PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
            container.start();
            postgres = container;
        }
        return postgres;
    }
}
