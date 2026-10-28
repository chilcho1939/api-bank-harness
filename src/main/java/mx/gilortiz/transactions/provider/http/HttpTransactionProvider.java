package mx.gilortiz.transactions.provider.http;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;
import mx.gilortiz.transactions.transaction.ProviderRequest;
import mx.gilortiz.transactions.transaction.ProviderResult;
import mx.gilortiz.transactions.transaction.TransactionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adaptador HTTP del proveedor. Traduce toda respuesta o excepción a un {@link ProviderResult}.
 * <p>
 * Orden de decoradores: {@code Retry(CircuitBreaker(llamada))}. Cada intento cuenta para el circuito y,
 * con el circuito abierto, el retry no insiste ({@link CallNotPermittedException} no es reintentable).
 */
public final class HttpTransactionProvider implements TransactionProvider, AutoCloseable {

    static final String EXECUTE_PATH = "/provider/v1/execute";

    static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    static final String PROVIDER_CIRCUIT_OPEN = "PROVIDER_CIRCUIT_OPEN";
    static final String PROVIDER_ERROR = "PROVIDER_ERROR";
    static final String PROVIDER_TIMEOUT = "PROVIDER_TIMEOUT";
    static final String PROVIDER_COMMUNICATION_ERROR = "PROVIDER_COMMUNICATION_ERROR";
    static final String PROVIDER_INVALID_RESPONSE = "PROVIDER_INVALID_RESPONSE";
    static final String PROVIDER_GATEWAY_TIMEOUT = "PROVIDER_GATEWAY_TIMEOUT";
    static final String PROVIDER_UNEXPECTED_ERROR = "PROVIDER_UNEXPECTED_ERROR";

    private static final Logger log = LoggerFactory.getLogger(HttpTransactionProvider.class);
    private static final String RESILIENCE_NAME = "provider";
    private static final int GATEWAY_TIMEOUT = 504;

    private final HttpClient httpClient;
    private final RestClient restClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    private HttpTransactionProvider(HttpClient httpClient, RestClient restClient, Retry retry,
                                    CircuitBreaker circuitBreaker) {
        this.httpClient = httpClient;
        this.restClient = restClient;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * @param restClientBuilder builder de Boot (Jackson y observabilidad configurados); se clona para no alterarlo
     */
    static HttpTransactionProvider create(RestClient.Builder restClientBuilder, ProviderProperties properties) {
        // HTTP/1.1 explícito: evita el upgrade a h2c que el cliente JDK intenta por defecto sobre http://.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = restClientBuilder.clone()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new HttpTransactionProvider(httpClient, restClient, retry(properties.retry()),
                circuitBreaker(properties.circuitBreaker()));
    }

    private static Retry retry(ProviderProperties.Retry properties) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(properties.waitDuration(),
                        properties.backoffMultiplier(), properties.randomizationFactor()))
                .retryExceptions(ProviderUnreachableException.class)
                .build();
        return Retry.of(RESILIENCE_NAME, config);
    }

    private static CircuitBreaker circuitBreaker(ProviderProperties.CircuitBreaker properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .recordExceptions(ProviderUnreachableException.class)
                // Fallas técnicas (5xx sin cuerpo, timeouts, respuesta inválida) llegan como resultado, no como excepción.
                // Rejected (rechazo de negocio) y Approved cuentan como llamadas exitosas.
                .recordResult(result -> result instanceof ProviderResult.Failed
                        || result instanceof ProviderResult.Unknown)
                .build();
        return CircuitBreaker.of(RESILIENCE_NAME, config);
    }

    @Override
    public ProviderResult execute(ProviderRequest request) {
        Supplier<ProviderResult> call = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> send(request)));
        try {
            return call.get();
        } catch (ProviderUnreachableException exception) {
            log.warn("Proveedor inalcanzable tras agotar los reintentos: {}",
                    rootCause(exception).getClass().getSimpleName());
            return new ProviderResult.Failed(PROVIDER_UNAVAILABLE, "No se pudo conectar con el proveedor");
        } catch (CallNotPermittedException exception) {
            log.warn("Circuito del proveedor abierto: no se envía la petición");
            return new ProviderResult.Failed(PROVIDER_CIRCUIT_OPEN,
                    "El proveedor no está disponible temporalmente");
        }
    }

    private ProviderResult send(ProviderRequest request) {
        try {
            return restClient.post()
                    .uri(EXECUTE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(ExecuteRequest.from(request))
                    .exchange((httpRequest, response) ->
                            toResult(response.getStatusCode(), readBody(response)));
        } catch (ResourceAccessException exception) {
            return onIoFailure(exception);
        } catch (RestClientException exception) {
            log.warn("Error inesperado al comunicarse con el proveedor: {}", exception.toString());
            return new ProviderResult.Unknown(PROVIDER_COMMUNICATION_ERROR,
                    "Error de comunicación con el proveedor; resultado incierto");
        } catch (RuntimeException exception) {
            // El puerto no lanza (A5). Un error no previsto puede ocurrir tras enviar la petición: resultado incierto.
            // ProviderUnreachableException no llega aquí: se lanza desde el catch de ResourceAccessException.
            log.error("Error no previsto al llamar al proveedor", exception);
            return new ProviderResult.Unknown(PROVIDER_UNEXPECTED_ERROR,
                    "Error no previsto al llamar al proveedor; resultado incierto");
        }
    }

    private static ExecuteResponse readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(ExecuteResponse.class);
        } catch (RestClientException unreadableBody) {
            // Cuerpo vacío, no JSON o con otra forma: se trata como "sin cuerpo" y decide el código HTTP.
            return null;
        }
    }

    private static ProviderResult toResult(HttpStatusCode status, ExecuteResponse body) {
        if (status.is2xxSuccessful()) {
            return body != null && body.isApproved()
                    ? new ProviderResult.Approved(body.transactionId(), body.balance(), body.executedAt())
                    : new ProviderResult.Unknown(PROVIDER_INVALID_RESPONSE,
                    "El proveedor respondió HTTP " + status.value() + " sin una aprobación válida");
        }
        if (status.isError() && body != null && body.hasError()) {
            // RN06: un 4xx o 5xx con cuerpo de rechazo es una decisión del proveedor, no una falla técnica.
            return new ProviderResult.Rejected(body.code(), body.message());
        }
        if (status.is4xxClientError()) {
            return new ProviderResult.Failed(PROVIDER_INVALID_RESPONSE,
                    "El proveedor respondió HTTP " + status.value() + " sin cuerpo de error");
        }
        if (status.value() == GATEWAY_TIMEOUT) {
            // Un intermediario dejó de esperar al proveedor: no prueba que la operación no se aplicó (B4).
            return new ProviderResult.Unknown(PROVIDER_GATEWAY_TIMEOUT,
                    "El proveedor respondió HTTP 504 sin cuerpo; resultado incierto");
        }
        if (status.is5xxServerError()) {
            return new ProviderResult.Failed(PROVIDER_ERROR,
                    "El proveedor respondió HTTP " + status.value() + " sin cuerpo");
        }
        return new ProviderResult.Unknown(PROVIDER_INVALID_RESPONSE,
                "El proveedor respondió HTTP " + status.value() + " inesperado");
    }

    private static ProviderResult onIoFailure(ResourceAccessException exception) {
        if (isConnectionFailure(exception)) {
            throw new ProviderUnreachableException(exception);
        }
        if (hasCause(exception, HttpTimeoutException.class)) {
            log.warn("Timeout de lectura del proveedor; resultado incierto");
            return new ProviderResult.Unknown(PROVIDER_TIMEOUT,
                    "El proveedor no respondió a tiempo; resultado incierto");
        }
        // La petición pudo llegar (p. ej. conexión cortada a mitad): no se reintenta y queda incierta.
        log.warn("Error de E/S con el proveedor; resultado incierto: {}", exception.toString());
        return new ProviderResult.Unknown(PROVIDER_COMMUNICATION_ERROR,
                "Error de comunicación con el proveedor; resultado incierto");
    }

    private static boolean isConnectionFailure(Throwable exception) {
        return hasCause(exception, ConnectException.class)
                || hasCause(exception, HttpConnectTimeoutException.class)
                || hasCause(exception, UnknownHostException.class)
                || hasCause(exception, NoRouteToHostException.class);
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    /** Expuesto para observar en tests los intentos y el estado del circuito. */
    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    /** Expuesto para observar en tests la política de espera entre reintentos. */
    Retry retry() {
        return retry;
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
