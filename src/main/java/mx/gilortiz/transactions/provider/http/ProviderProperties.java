package mx.gilortiz.transactions.provider.http;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del cliente del proveedor externo ({@code provider.*}).
 * <p>
 * Se valida al construirse: una configuración inválida impide arrancar con un mensaje que nombra la propiedad.
 */
@ConfigurationProperties("provider")
public record ProviderProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Retry retry,
        CircuitBreaker circuitBreaker) {

    public ProviderProperties {
        Objects.requireNonNull(baseUrl, "provider.base-url es obligatorio");
        Objects.requireNonNull(retry, "provider.retry es obligatorio");
        Objects.requireNonNull(circuitBreaker, "provider.circuit-breaker es obligatorio");
        requirePositive(connectTimeout, "provider.connect-timeout");
        requirePositive(readTimeout, "provider.read-timeout");
        // El timeout de lectura del cliente JDK corre desde el envío e incluye la conexión: si no es mayor,
        // una conexión lenta se reportaría como timeout de lectura (UNKNOWN sin reintento) en vez de FAILED.
        if (readTimeout.compareTo(connectTimeout) <= 0) {
            throw new IllegalArgumentException("provider.read-timeout (" + readTimeout
                    + ") debe ser mayor que provider.connect-timeout (" + connectTimeout + ")");
        }
    }

    /**
     * Reintentos ante errores de conexión con backoff exponencial y jitter.
     *
     * @param maxAttempts         intentos totales, incluido el primero
     * @param waitDuration        espera antes del primer reintento
     * @param backoffMultiplier   factor por el que crece la espera en cada reintento
     * @param randomizationFactor jitter: la espera varía ±este porcentaje (0 = sin jitter)
     */
    public record Retry(int maxAttempts, Duration waitDuration, double backoffMultiplier,
                        double randomizationFactor) {

        public Retry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("provider.retry.max-attempts debe ser al menos 1: " + maxAttempts);
            }
            requirePositive(waitDuration, "provider.retry.wait-duration");
            if (backoffMultiplier < 1) {
                throw new IllegalArgumentException(
                        "provider.retry.backoff-multiplier debe ser al menos 1: " + backoffMultiplier);
            }
            if (randomizationFactor < 0 || randomizationFactor >= 1) {
                throw new IllegalArgumentException(
                        "provider.retry.randomization-factor debe estar en [0, 1): " + randomizationFactor);
            }
        }
    }

    /**
     * Circuit breaker por conteo de llamadas.
     */
    public record CircuitBreaker(
            float failureRateThreshold,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            Duration waitDurationInOpenState) {

        public CircuitBreaker {
            if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
                throw new IllegalArgumentException(
                        "provider.circuit-breaker.failure-rate-threshold debe estar en (0, 100]: "
                                + failureRateThreshold);
            }
            if (slidingWindowSize < 1) {
                throw new IllegalArgumentException(
                        "provider.circuit-breaker.sliding-window-size debe ser al menos 1: " + slidingWindowSize);
            }
            if (minimumNumberOfCalls < 1) {
                throw new IllegalArgumentException(
                        "provider.circuit-breaker.minimum-number-of-calls debe ser al menos 1: "
                                + minimumNumberOfCalls);
            }
            requirePositive(waitDurationInOpenState, "provider.circuit-breaker.wait-duration-in-open-state");
        }
    }

    private static void requirePositive(Duration value, String property) {
        Objects.requireNonNull(value, property + " es obligatorio");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " debe ser positivo: " + value);
        }
    }
}
