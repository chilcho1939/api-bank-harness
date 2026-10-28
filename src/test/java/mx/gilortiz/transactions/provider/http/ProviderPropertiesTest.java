package mx.gilortiz.transactions.provider.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderPropertiesTest {

    private static final URI BASE_URL = URI.create("http://localhost:8089");
    private static final ProviderProperties.Retry RETRY =
            new ProviderProperties.Retry(3, Duration.ofMillis(200), 2, 0.5);
    private static final ProviderProperties.CircuitBreaker CIRCUIT_BREAKER =
            new ProviderProperties.CircuitBreaker(50, 20, 10, Duration.ofSeconds(30));

    @ParameterizedTest(name = "connect-timeout {0} ms y read-timeout {1} ms se rechazan")
    @DisplayName("read-timeout debe ser mayor que connect-timeout")
    @CsvSource({"1000, 1000", "1000, 999", "3000, 1000"})
    void rejectsReadTimeoutNotGreaterThanConnectTimeout(long connectMillis, long readMillis) {
        assertThatThrownBy(() -> new ProviderProperties(BASE_URL, Duration.ofMillis(connectMillis),
                Duration.ofMillis(readMillis), RETRY, CIRCUIT_BREAKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.read-timeout")
                .hasMessageContaining("provider.connect-timeout");
    }

    @Test
    @DisplayName("read-timeout 1.001 s con connect-timeout 1 s se acepta")
    void acceptsReadTimeoutJustAboveConnectTimeout() {
        assertThatCode(() -> new ProviderProperties(BASE_URL, Duration.ofSeconds(1), Duration.ofMillis(1001),
                RETRY, CIRCUIT_BREAKER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un connect-timeout en cero se rechaza nombrando la propiedad")
    void rejectsZeroConnectTimeout() {
        assertThatThrownBy(() -> new ProviderProperties(BASE_URL, Duration.ZERO, Duration.ofSeconds(3),
                RETRY, CIRCUIT_BREAKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.connect-timeout");
    }

    @Test
    @DisplayName("una base-url ausente se rechaza nombrando la propiedad")
    void rejectsMissingBaseUrl() {
        assertThatThrownBy(() -> new ProviderProperties(null, Duration.ofSeconds(1), Duration.ofSeconds(3),
                RETRY, CIRCUIT_BREAKER))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("provider.base-url");
    }

    @ParameterizedTest(name = "max-attempts {0} se rechaza")
    @DisplayName("retry.max-attempts debe ser al menos 1")
    @ValueSource(ints = {0, -1})
    void rejectsMaxAttemptsBelowOne(int maxAttempts) {
        assertThatThrownBy(() -> new ProviderProperties.Retry(maxAttempts, Duration.ofMillis(200), 2, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.retry.max-attempts");
    }

    @Test
    @DisplayName("retry.wait-duration ausente se rechaza nombrando la propiedad")
    void rejectsMissingWaitDuration() {
        assertThatThrownBy(() -> new ProviderProperties.Retry(3, null, 2, 0.5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("provider.retry.wait-duration");
    }

    @Test
    @DisplayName("retry.backoff-multiplier menor que 1 se rechaza")
    void rejectsBackoffMultiplierBelowOne() {
        assertThatThrownBy(() -> new ProviderProperties.Retry(3, Duration.ofMillis(200), 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.retry.backoff-multiplier");
    }

    @ParameterizedTest(name = "randomization-factor {0} se rechaza")
    @DisplayName("retry.randomization-factor debe estar en [0, 1)")
    @ValueSource(doubles = {-0.1, 1.0, 1.5})
    void rejectsRandomizationFactorOutOfRange(double factor) {
        assertThatThrownBy(() -> new ProviderProperties.Retry(3, Duration.ofMillis(200), 2, factor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.retry.randomization-factor");
    }

    @ParameterizedTest(name = "failure-rate-threshold {0} se rechaza")
    @DisplayName("circuit-breaker.failure-rate-threshold debe estar en (0, 100]")
    @ValueSource(floats = {0, -1, 100.5f})
    void rejectsFailureRateOutOfRange(float threshold) {
        assertThatThrownBy(() -> new ProviderProperties.CircuitBreaker(threshold, 20, 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.circuit-breaker.failure-rate-threshold");
    }

    @Test
    @DisplayName("circuit-breaker.sliding-window-size y minimum-number-of-calls en cero se rechazan")
    void rejectsZeroWindowAndMinimumCalls() {
        assertThatThrownBy(() -> new ProviderProperties.CircuitBreaker(50, 0, 10, Duration.ofSeconds(30)))
                .hasMessageContaining("provider.circuit-breaker.sliding-window-size");
        assertThatThrownBy(() -> new ProviderProperties.CircuitBreaker(50, 20, 0, Duration.ofSeconds(30)))
                .hasMessageContaining("provider.circuit-breaker.minimum-number-of-calls");
    }

    @Test
    @DisplayName("circuit-breaker.wait-duration-in-open-state en cero se rechaza")
    void rejectsZeroOpenStateWait() {
        assertThatThrownBy(() -> new ProviderProperties.CircuitBreaker(50, 20, 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider.circuit-breaker.wait-duration-in-open-state");
    }
}
