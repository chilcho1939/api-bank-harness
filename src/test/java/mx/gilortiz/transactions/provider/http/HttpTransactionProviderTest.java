package mx.gilortiz.transactions.provider.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalBiFunction;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import mx.gilortiz.transactions.transaction.ProviderRequest;
import mx.gilortiz.transactions.transaction.ProviderResult;
import mx.gilortiz.transactions.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class HttpTransactionProviderTest {

    private static final String EXECUTE_PATH = "/provider/v1/execute";
    // Holgados para runners de CI lentos: el read-timeout del cliente JDK cubre todo el intercambio.
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);
    private static final int SLOW_RESPONSE_DELAY_MILLIS = (int) READ_TIMEOUT.multipliedBy(2).toMillis();
    private static final Duration RETRY_WAIT = Duration.ofMillis(10);

    private static final ProviderRequest CREDIT_REQUEST = new ProviderRequest(
            "acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN");

    private static final String APPROVED_BODY = """
            { "transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00, "executedAt": "2025-03-15T10:30:00Z" }
            """;
    private static final String INSUFFICIENT_FUNDS_BODY = """
            { "status": "REJECTED", "code": "INSUFFICIENT_FUNDS", "message": "The account does not have enough balance to complete the transaction" }
            """;
    private static final ProviderResult.Rejected INSUFFICIENT_FUNDS = new ProviderResult.Rejected("INSUFFICIENT_FUNDS",
            "The account does not have enough balance to complete the transaction");

    @RegisterExtension
    static final WireMockExtension PROVIDER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private HttpTransactionProvider provider;

    @AfterEach
    void closeProvider() {
        if (provider != null) {
            provider.close();
        }
    }

    @Nested
    @DisplayName("contrato y traducción de respuestas")
    class Responses {

        @Test
        @DisplayName("envía POST /provider/v1/execute con el cuerpo literal de la spec")
        void sendsLiteralContract() {
            stubProvider(json(200, APPROVED_BODY));
            provider = providerWithDefaults();

            provider.execute(CREDIT_REQUEST);

            PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withRequestBody(equalToJson("""
                            { "accountId": "acc-123456", "type": "CREDIT", "amount": 1500.00, "currency": "MXN" }
                            """)));
            assertThat(PROVIDER.getAllServeEvents().getFirst().getRequest().getBodyAsString())
                    .contains("\"amount\":1500.00")
                    .doesNotContain("description");
        }

        @Test
        @DisplayName("RN05: un 200 APPROVED se traduce a aprobado con transactionId, balance y executedAt")
        void approvedResponse() {
            stubProvider(json(200, APPROVED_BODY));
            provider = providerWithDefaults();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Approved.class, approved -> {
                assertThat(approved.transactionId()).isEqualTo("txn-789");
                assertThat(approved.balance()).isEqualByComparingTo("5500.00");
                assertThat(approved.executedAt()).isEqualTo(Instant.parse("2025-03-15T10:30:00Z"));
            });
        }

        @ParameterizedTest(name = "HTTP {0} con cuerpo → rechazado")
        @DisplayName("RN06: un 4xx o 5xx con cuerpo se traduce a rechazado con el code y message del proveedor")
        @ValueSource(ints = {400, 402, 409, 422, 500, 503, 504})
        void errorWithBodyIsRejected(int status) {
            stubProvider(json(status, INSUFFICIENT_FUNDS_BODY));
            provider = providerWithDefaults();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isEqualTo(INSUFFICIENT_FUNDS);
            PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        }

        @ParameterizedTest(name = "HTTP {0} sin cuerpo → falla sin reintento")
        @DisplayName("un 500, 502 o 503 sin cuerpo se traduce a falla PROVIDER_ERROR y no se reintenta")
        @ValueSource(ints = {500, 502, 503})
        void serverErrorWithoutBodyIsFailed(int status) {
            stubProvider(aResponse().withStatus(status));
            provider = providerWithDefaults();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Failed.class, failed -> {
                assertThat(failed.code()).isEqualTo(HttpTransactionProvider.PROVIDER_ERROR);
                assertThat(failed.message()).contains(String.valueOf(status));
            });
            PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        }

        @Test
        @DisplayName("un 504 sin cuerpo se traduce a desconocido: el proveedor pudo aplicarla; no se reintenta")
        void gatewayTimeoutWithoutBodyIsUnknown() {
            stubProvider(aResponse().withStatus(504));
            provider = providerWithDefaults();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Unknown.class, unknown ->
                    assertThat(unknown.code()).isEqualTo(HttpTransactionProvider.PROVIDER_GATEWAY_TIMEOUT));
            PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        }

        @Test
        @DisplayName("un 4xx sin cuerpo de error se traduce a falla con PROVIDER_INVALID_RESPONSE")
        void clientErrorWithoutBodyIsFailed() {
            stubProvider(aResponse().withStatus(404));
            provider = providerWithDefaults();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Failed.class, failed ->
                    assertThat(failed.code()).isEqualTo(HttpTransactionProvider.PROVIDER_INVALID_RESPONSE));
        }

        @ParameterizedTest(name = "200 con {0} → desconocido")
        @DisplayName("un 200 sin aprobación completa (status, transactionId, balance, executedAt) se traduce a desconocido")
        @ValueSource(strings = {
                "{ \"status\": \"PROCESSING\" }",
                "{ \"transactionId\": \"txn-789\", \"status\": \"APPROVED\", \"balance\": 5500.00 }",
                "{ \"status\": \"APPROVED\", \"balance\": 5500.00, \"executedAt\": \"2025-03-15T10:30:00Z\" }",
                "{ \"transactionId\": \"txn-789\", \"status\": \"APPROVED\", \"executedAt\": \"2025-03-15T10:30:00Z\" }"
        })
        void successWithoutCompleteApprovalIsUnknown(String body) {
            stubProvider(json(200, body));
            provider = providerWithDefaults();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Unknown.class, unknown ->
                    assertThat(unknown.code()).isEqualTo(HttpTransactionProvider.PROVIDER_INVALID_RESPONSE));
        }

        @Test
        @DisplayName("un error no previsto en el cliente se traduce a desconocido, no se reintenta y no se propaga")
        void unexpectedRuntimeExceptionIsUnknown() {
            RestClient.Builder failingBuilder = RestClient.builder().requestInterceptor((request, body, execution) -> {
                throw new IllegalStateException("falla forzada en el test");
            });
            provider = HttpTransactionProvider.create(failingBuilder, properties(baseUrl(), circuitBreakerThatStaysClosed()));

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Unknown.class, unknown ->
                    assertThat(unknown.code()).isEqualTo(HttpTransactionProvider.PROVIDER_UNEXPECTED_ERROR));
            assertThat(provider.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("timeouts y reintentos")
    class TimeoutsAndRetries {

        @Test
        @DisplayName("un timeout de lectura se traduce a desconocido y NO se reintenta")
        void readTimeoutIsUnknownWithoutRetry() {
            stubProvider(json(200, APPROVED_BODY).withFixedDelay(SLOW_RESPONSE_DELAY_MILLIS));
            provider = providerWithDefaults();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Unknown.class, unknown ->
                    assertThat(unknown.code()).isEqualTo(HttpTransactionProvider.PROVIDER_TIMEOUT));
            // Cada intento pasa por el circuito: una sola llamada registrada = sin reintento (determinista).
            assertThat(provider.circuitBreaker().getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
            awaitProviderRequests(1);
        }

        @Test
        @DisplayName("una conexión rechazada se reintenta 3 veces y termina en falla PROVIDER_UNAVAILABLE")
        void connectionRefusedIsRetriedThenFailed() throws IOException {
            provider = HttpTransactionProvider.create(RestClient.builder(),
                    properties(URI.create("http://localhost:" + closedPort()), circuitBreakerThatStaysClosed()));

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Failed.class, failed ->
                    assertThat(failed.code()).isEqualTo(HttpTransactionProvider.PROVIDER_UNAVAILABLE));
            assertThat(provider.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        }

        @Test
        @DisplayName("una conexión cortada por el proveedor a mitad de la petición es desconocida y NO se reintenta")
        void connectionResetIsUnknownWithoutRetry() {
            stubProvider(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));
            provider = providerWithDefaults();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Unknown.class, unknown ->
                    assertThat(unknown.code()).isEqualTo(HttpTransactionProvider.PROVIDER_COMMUNICATION_ERROR));
            PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        }

        @Test
        @DisplayName("la espera entre reintentos crece exponencialmente según backoff-multiplier")
        void retryWaitGrowsExponentially() {
            provider = HttpTransactionProvider.create(RestClient.builder(), new ProviderProperties(baseUrl(),
                    CONNECT_TIMEOUT, READ_TIMEOUT,
                    new ProviderProperties.Retry(3, Duration.ofMillis(100), 2, 0),
                    circuitBreakerThatStaysClosed()));

            IntervalBiFunction<Object> interval = provider.retry().getRetryConfig().getIntervalBiFunction();

            assertThat(interval.apply(1, null)).isEqualTo(100L);
            assertThat(interval.apply(2, null)).isEqualTo(200L);
        }

        @Test
        @DisplayName("el jitter mantiene cada espera dentro de ±randomization-factor del valor exponencial")
        void retryWaitHasJitterWithinBounds() {
            provider = HttpTransactionProvider.create(RestClient.builder(), new ProviderProperties(baseUrl(),
                    CONNECT_TIMEOUT, READ_TIMEOUT,
                    new ProviderProperties.Retry(3, Duration.ofMillis(100), 2, 0.5),
                    circuitBreakerThatStaysClosed()));

            IntervalBiFunction<Object> interval = provider.retry().getRetryConfig().getIntervalBiFunction();

            for (int sample = 0; sample < 50; sample++) {
                assertThat(interval.apply(1, null)).isBetween(50L, 150L);
                assertThat(interval.apply(2, null)).isBetween(100L, 300L);
            }
        }
    }

    @Nested
    @DisplayName("circuit breaker")
    class CircuitBreakerBehaviour {

        private static final ProviderProperties.CircuitBreaker OPENS_AFTER_TWO_FAILURES =
                new ProviderProperties.CircuitBreaker(50, 2, 2, Duration.ofMinutes(1));

        @Test
        @DisplayName("con el circuito abierto no se llama al proveedor y el resultado es falla PROVIDER_CIRCUIT_OPEN")
        void openCircuitDoesNotCallProvider() {
            stubProvider(aResponse().withStatus(503));
            provider = providerWith(OPENS_AFTER_TWO_FAILURES);
            provider.execute(CREDIT_REQUEST);
            provider.execute(CREDIT_REQUEST);
            assertThat(provider.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
            PROVIDER.resetRequests();

            ProviderResult result = provider.execute(CREDIT_REQUEST);

            assertThat(result).isInstanceOfSatisfying(ProviderResult.Failed.class, failed ->
                    assertThat(failed.code()).isEqualTo(HttpTransactionProvider.PROVIDER_CIRCUIT_OPEN));
            PROVIDER.verify(0, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        }

        @ParameterizedTest(name = "HTTP {0} con cuerpo de rechazo no abre el circuito")
        @DisplayName("los rechazos del proveedor (4xx o 5xx con cuerpo) no abren el circuito")
        @ValueSource(ints = {422, 500})
        void rejectionsDoNotOpenCircuit(int status) {
            stubProvider(json(status, INSUFFICIENT_FUNDS_BODY));
            provider = providerWith(OPENS_AFTER_TWO_FAILURES);

            for (int call = 0; call < 5; call++) {
                assertThat(provider.execute(CREDIT_REQUEST)).isEqualTo(INSUFFICIENT_FUNDS);
            }

            assertThat(provider.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            PROVIDER.verify(5, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        }

        @Test
        @DisplayName("los 504 sin cuerpo cuentan como falla técnica y abren el circuito")
        void gatewayTimeoutsOpenCircuit() {
            stubProvider(aResponse().withStatus(504));
            provider = providerWith(OPENS_AFTER_TWO_FAILURES);

            provider.execute(CREDIT_REQUEST);
            provider.execute(CREDIT_REQUEST);

            assertThat(provider.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("los timeouts de lectura cuentan como falla técnica y abren el circuito")
        void readTimeoutsOpenCircuit() {
            stubProvider(json(200, APPROVED_BODY).withFixedDelay(SLOW_RESPONSE_DELAY_MILLIS));
            provider = providerWith(OPENS_AFTER_TWO_FAILURES);

            provider.execute(CREDIT_REQUEST);
            provider.execute(CREDIT_REQUEST);

            assertThat(provider.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("una conexión rechazada con reintentos agotados abre el circuito")
        void connectionRefusedOpensCircuit() throws IOException {
            provider = HttpTransactionProvider.create(RestClient.builder(),
                    properties(URI.create("http://localhost:" + closedPort()), OPENS_AFTER_TWO_FAILURES));

            provider.execute(CREDIT_REQUEST);

            assertThat(provider.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(provider.execute(CREDIT_REQUEST)).isInstanceOfSatisfying(ProviderResult.Failed.class,
                    failed -> assertThat(failed.code()).isEqualTo(HttpTransactionProvider.PROVIDER_CIRCUIT_OPEN));
        }
    }

    private static HttpTransactionProvider providerWithDefaults() {
        return providerWith(circuitBreakerThatStaysClosed());
    }

    private static HttpTransactionProvider providerWith(ProviderProperties.CircuitBreaker circuitBreaker) {
        return HttpTransactionProvider.create(RestClient.builder(), properties(baseUrl(), circuitBreaker));
    }

    private static ProviderProperties properties(URI baseUrl, ProviderProperties.CircuitBreaker circuitBreaker) {
        return new ProviderProperties(baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT,
                new ProviderProperties.Retry(3, RETRY_WAIT, 2, 0.5), circuitBreaker);
    }

    private static ProviderProperties.CircuitBreaker circuitBreakerThatStaysClosed() {
        return new ProviderProperties.CircuitBreaker(100, 100, 100, Duration.ofMinutes(1));
    }

    private static URI baseUrl() {
        return URI.create(PROVIDER.baseUrl());
    }

    /**
     * WireMock puede registrar la petición después de cumplir el {@code withFixedDelay}, cuando el cliente ya
     * abandonó por timeout: se espera de forma acotada a que aparezca en el journal.
     */
    private static void awaitProviderRequests(int expected) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> PROVIDER.verify(expected, postRequestedFor(urlEqualTo(EXECUTE_PATH))));
    }

    private static void stubProvider(ResponseDefinitionBuilder response) {
        PROVIDER.stubFor(post(urlEqualTo(EXECUTE_PATH)).willReturn(response));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
