package mx.gilortiz.transactions.transaction;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static mx.gilortiz.transactions.transaction.ProviderStubs.APPROVED_BODY;
import static mx.gilortiz.transactions.transaction.ProviderStubs.EXECUTE_PATH;
import static mx.gilortiz.transactions.transaction.ProviderStubs.INSUFFICIENT_FUNDS;
import static mx.gilortiz.transactions.transaction.ProviderStubs.INSUFFICIENT_FUNDS_MESSAGE;
import static mx.gilortiz.transactions.transaction.ProviderStubs.REJECTED_BODY;
import static mx.gilortiz.transactions.transaction.ProviderStubs.SPEC_REQUEST;
import static mx.gilortiz.transactions.transaction.ProviderStubs.json;
import static mx.gilortiz.transactions.transaction.ProviderStubs.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import mx.gilortiz.transactions.DatabaseCleaner;
import mx.gilortiz.transactions.PostgresTestConfiguration;
import mx.gilortiz.transactions.common.web.PageResponse;
import mx.gilortiz.transactions.transaction.TransactionApi.HttpResult;
import mx.gilortiz.transactions.transaction.dto.TransactionResponse;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flujo completo sin mocks internos: HTTP real contra el servidor embebido → reglas → proveedor (WireMock) →
 * Postgres (Testcontainers). El timeout del proveedor vive en {@link TransactionTimeoutIT}, con su propio
 * read-timeout corto.
 * <p>
 * Determinismo: el proveedor "lento" es una compuerta ({@link ProviderGate}) que retiene las peticiones hasta que
 * el test la abre; las esperas son condiciones observables con Awaitility, nunca sleeps. El read-timeout del
 * proveedor ({@value #PROVIDER_READ_TIMEOUT}) duplica la espera máxima con peticiones retenidas
 * ({@code MAX_WAIT_WHILE_HELD}), para que un runner lento nunca convierta una retenida en UNKNOWN.
 * <p>
 * El pool de Hikari es de {@value #POOL_SIZE} conexiones para demostrar que la llamada al proveedor no retiene
 * ninguna. El circuit breaker se configura para no abrirse aquí: se prueba en {@code HttpTransactionProviderTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "provider.connect-timeout=500ms",
        "provider.read-timeout=" + TransactionFlowIT.PROVIDER_READ_TIMEOUT,
        "provider.circuit-breaker.sliding-window-size=100",
        "provider.circuit-breaker.minimum-number-of-calls=100",
        "spring.datasource.hikari.maximum-pool-size=" + TransactionFlowIT.POOL_SIZE})
@Import(PostgresTestConfiguration.class)
class TransactionFlowIT {

    static final String PROVIDER_READ_TIMEOUT = "10s";
    static final int POOL_SIZE = 2;

    private static final Duration MAX_WAIT_WHILE_HELD = Duration.ofSeconds(5);
    private static final Duration MAX_WAIT = Duration.ofSeconds(30);
    private static final int CONCURRENT_REQUESTS = 10;
    private static final TransactionSearchCriteria PENDING_ONLY =
            new TransactionSearchCriteria(null, TransactionStatus.PENDING, null);
    private static final TransactionSearchCriteria EXECUTED_ONLY =
            new TransactionSearchCriteria(null, TransactionStatus.EXECUTED, null);

    private static final ProviderGate GATE = new ProviderGate();

    @RegisterExtension
    static final WireMockExtension PROVIDER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().containerThreads(4 * CONCURRENT_REQUESTS).extensions(GATE))
            .build();

    private final TransactionApi api;
    private final TransactionRepository repository;
    private final DatabaseCleaner databaseCleaner;
    private final JdbcTemplate jdbc;
    private final HikariPoolMXBean pool;

    @Autowired
    TransactionFlowIT(@LocalServerPort int port, JsonMapper mapper, TransactionRepository repository,
                      DatabaseCleaner databaseCleaner, JdbcTemplate jdbc, DataSource dataSource)
            throws SQLException {
        this.api = new TransactionApi(port, mapper);
        this.repository = repository;
        this.databaseCleaner = databaseCleaner;
        this.jdbc = jdbc;
        this.pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", PROVIDER::baseUrl);
    }

    @BeforeEach
    void resetState() {
        databaseCleaner.clean();
        PROVIDER.resetAll();
        GATE.reset();
    }

    // Red de seguridad si un test falla con peticiones retenidas. En el camino feliz cada test ya consumió las
    // respuestas que retuvo, así que WireMock ya las registró y no contaminan el journal del siguiente.
    @AfterEach
    void releaseHeldRequests() {
        GATE.open();
    }

    @Test
    @DisplayName("RN05: una aprobación del proveedor responde 201 EXECUTED y persiste providerTransactionId y "
            + "balanceAfter")
    void approvedIsPersistedAsExecuted() {
        stubProvider(json(200, APPROVED_BODY));

        HttpResult result = api.post(SPEC_REQUEST, null);

        assertThat(result.status()).isEqualTo(201);
        TransactionResponse response = result.transaction();
        assertThat(response.accountId()).isEqualTo("acc-123456");
        assertThat(response.type()).isEqualTo(TransactionType.CREDIT);
        assertThat(response.amount()).isEqualByComparingTo("1500.00");
        assertThat(response.currency()).isEqualTo("MXN");
        assertThat(response.description()).isEqualTo("Transferencia recibida");
        assertThat(response.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(response.providerTransactionId()).isEqualTo("txn-789");
        assertThat(response.balanceAfter()).isEqualByComparingTo("5500.00");

        Transaction stored = repository.findById(response.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(stored.providerTransactionId()).isEqualTo("txn-789");
        assertThat(stored.balanceAfter()).isEqualByComparingTo("5500.00");
        assertThat(stored.code()).isNull();
        assertThat(stored.message()).isNull();
        assertThat(stored.createdAt()).isEqualTo(response.createdAt());

        PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)).withRequestBody(equalToJson("""
                { "accountId": "acc-123456", "type": "CREDIT", "amount": 1500.00, "currency": "MXN" }
                """)));
    }

    @ParameterizedTest(name = "HTTP {0} con cuerpo de rechazo")
    @ValueSource(ints = {400, 422, 500, 503})
    @DisplayName("RN06: un rechazo 4xx/5xx del proveedor responde 201 REJECTED y persiste su code y message")
    void rejectionIsPersistedWithCodeAndMessage(int providerStatus) {
        stubProvider(json(providerStatus, REJECTED_BODY));

        HttpResult result = api.post(SPEC_REQUEST, null);

        assertThat(result.status()).isEqualTo(201);
        TransactionResponse response = result.transaction();
        assertThat(response.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(response.providerTransactionId()).isNull();
        assertThat(response.balanceAfter()).isNull();

        Transaction stored = repository.findById(response.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(stored.code()).isEqualTo(INSUFFICIENT_FUNDS);
        assertThat(stored.message()).isEqualTo(INSUFFICIENT_FUNDS_MESSAGE);
        assertThat(stored.providerTransactionId()).isNull();
        assertThat(stored.balanceAfter()).isNull();
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
    }

    @ParameterizedTest(name = "{0} {1} {2} → {3}")
    @CsvSource({
            "CREDIT, 1.00,     MXN, AMOUNT_TOO_LOW",
            "DEBIT,  1.00,     MXN, AMOUNT_TOO_LOW",
            "DEBIT,  10000.01, MXN, DEBIT_AMOUNT_EXCEEDS_LIMIT",
            "CREDIT, 1500.00,  USD, UNSUPPORTED_CURRENCY"})
    @DisplayName("RN04: una regla RN01–RN03 violada responde 422 con su code, no persiste nada y el proveedor no "
            + "recibe ninguna llamada")
    void businessRuleViolationNeverReachesProvider(String type, String amount, String currency, String code) {
        // El proveedor aprobaría: si se le llamara, el test lo detectaría.
        stubProvider(json(200, APPROVED_BODY));

        HttpResult result = api.post(request("acc-123456", type, amount, currency), "rule-key");

        assertThat(result.status()).isEqualTo(422);
        assertThat(result.contentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.json().path("code").asString()).isEqualTo(code);
        assertThat(PROVIDER.getAllServeEvents()).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"CREDIT, 1.01", "DEBIT, 1.01", "DEBIT, 10000.00", "CREDIT, 10000.01"})
    @DisplayName("RN01/RN02: los montos en el borde permitido llegan al proveedor y quedan EXECUTED")
    void boundaryAmountsReachProvider(String type, String amount) {
        stubProvider(json(200, APPROVED_BODY));

        HttpResult result = api.post(request("acc-123456", type, amount, "MXN"), null);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.EXECUTED);
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)).withRequestBody(equalToJson("""
                { "accountId": "acc-123456", "type": "%s", "amount": %s, "currency": "MXN" }
                """.formatted(type, amount))));
    }

    @Test
    @DisplayName("replay: la misma Idempotency-Key y el mismo payload responde 200 con la misma transacción sin "
            + "volver a llamar al proveedor")
    void replayReturnsSameTransaction() {
        stubProvider(json(200, APPROVED_BODY));

        HttpResult first = api.post(SPEC_REQUEST, "replay-key");
        HttpResult replay = api.post(SPEC_REQUEST, "replay-key");

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.transaction()).isEqualTo(first.transaction());
        assertThat(replay.transaction().status()).isEqualTo(TransactionStatus.EXECUTED);
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("la misma Idempotency-Key con otro payload responde 409 IDEMPOTENCY_KEY_CONFLICT y no llama al "
            + "proveedor")
    void sameKeyWithOtherPayloadIsConflict() {
        stubProvider(json(200, APPROVED_BODY));
        HttpResult first = api.post(SPEC_REQUEST, "conflict-key");

        HttpResult conflict = api.post(request("acc-123456", "CREDIT", "2000.00", "MXN"), "conflict-key");

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.json().path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        Transaction stored = repository.findById(first.transaction().id()).orElseThrow();
        assertThat(stored.amount()).isEqualByComparingTo("1500.00");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrencia: " + CONCURRENT_REQUESTS + " requests simultáneas con la misma Idempotency-Key "
            + "crean una sola fila, llaman una sola vez al proveedor y devuelven el mismo id")
    void concurrentRequestsWithSameKeyExecuteOnce() throws Exception {
        stubProvider(json(200, APPROVED_BODY).withTransformers(ProviderGate.NAME));
        AtomicInteger completed = new AtomicInteger();

        // Mientras la ganadora está retenida en el proveedor, todas las demás ya respondieron.
        List<HttpResult> results = sendConcurrently(i -> "race-key", completed,
                () -> awaitAtMost(MAX_WAIT_WHILE_HELD).untilAsserted(() -> {
                    assertThat(GATE.arrivals()).isEqualTo(1);
                    assertThat(completed).hasValue(CONCURRENT_REQUESTS - 1);
                }));

        List<TransactionResponse> created = results.stream().filter(r -> r.status() == 201)
                .map(HttpResult::transaction).toList();
        List<TransactionResponse> replayed = results.stream().filter(r -> r.status() == 200)
                .map(HttpResult::transaction).toList();
        assertThat(created).hasSize(1);
        assertThat(replayed).hasSize(CONCURRENT_REQUESTS - 1);
        UUID id = created.getFirst().id();
        assertThat(created.getFirst().status()).isEqualTo(TransactionStatus.EXECUTED);
        // Las repeticiones respondieron mientras la ganadora esperaba al proveedor: la ven en PENDING.
        assertThat(replayed).allSatisfy(replay -> {
            assertThat(replay.id()).isEqualTo(id);
            assertThat(replay.status()).isEqualTo(TransactionStatus.PENDING);
        });

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(id)).get().extracting(Transaction::status)
                .isEqualTo(TransactionStatus.EXECUTED);
        assertThat(GATE.arrivals()).isEqualTo(1);
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
    }

    @Test
    @DisplayName("sin conexión retenida: con un pool de " + POOL_SIZE + " conexiones, " + CONCURRENT_REQUESTS
            + " requests esperan al proveedor a la vez con su fila PENDING ya confirmada y 0 conexiones activas")
    void providerCallHoldsNoDatabaseConnection() throws Exception {
        stubProvider(json(200, APPROVED_BODY).withTransformers(ProviderGate.NAME));
        AtomicInteger completed = new AtomicInteger();

        List<HttpResult> results = sendConcurrently(i -> "pool-key-" + i, completed, () -> {
            // Si cada llamada retuviera una conexión, solo POOL_SIZE requests podrían llegar al proveedor.
            awaitAtMost(MAX_WAIT_WHILE_HELD)
                    .untilAsserted(() -> assertThat(GATE.arrivals()).isEqualTo(CONCURRENT_REQUESTS));
            // Todas están retenidas en el proveedor: el INSERT ya se confirmó (visible desde otra conexión) y
            // ninguna conserva una conexión del pool.
            assertThat(repository.count(PENDING_ONLY.toSpecification())).isEqualTo(CONCURRENT_REQUESTS);
            assertThat(pool.getActiveConnections()).isZero();
            assertThat(completed).hasValue(0);
        });

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.transaction().status()).isEqualTo(TransactionStatus.EXECUTED);
        });
        assertThat(repository.count(EXECUTED_ONLY.toSpecification())).isEqualTo(CONCURRENT_REQUESTS);
        PROVIDER.verify(CONCURRENT_REQUESTS, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
    }

    @Test
    @DisplayName("RN07: GET /transactions filtra por accountId, status y type (combinables) y pagina sin repetir "
            + "ni perder filas")
    void searchFiltersAndPaginates() {
        TransactionResponse creditA = create("acc-A", "CREDIT", json(200, APPROVED_BODY));
        TransactionResponse rejectedDebitA = create("acc-A", "DEBIT", json(422, REJECTED_BODY));
        TransactionResponse debitB = create("acc-B", "DEBIT", json(200, APPROVED_BODY));
        TransactionResponse secondCreditA = create("acc-A", "CREDIT", json(200, APPROVED_BODY));
        TransactionResponse rejectedCreditB = create("acc-B", "CREDIT", json(400, REJECTED_BODY));

        assertThat(ids(api.search("accountId=acc-A"))).containsExactlyInAnyOrder(
                creditA.id(), rejectedDebitA.id(), secondCreditA.id());
        assertThat(ids(api.search("status=REJECTED"))).containsExactlyInAnyOrder(
                rejectedDebitA.id(), rejectedCreditB.id());
        assertThat(ids(api.search("type=DEBIT"))).containsExactlyInAnyOrder(rejectedDebitA.id(), debitB.id());
        assertThat(ids(api.search("accountId=acc-A&status=EXECUTED&type=CREDIT"))).containsExactlyInAnyOrder(
                creditA.id(), secondCreditA.id());

        // Oráculo independiente del código bajo prueba: el orden lo da Postgres (uuid se compara sin signo).
        List<UUID> expectedOrder = jdbc.queryForList(
                "SELECT id FROM transactions ORDER BY created_at DESC, id DESC", UUID.class);
        assertThat(expectedOrder).hasSize(5);
        List<UUID> visited = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            PageResponse<TransactionResponse> result = api.search("page=" + page + "&limit=2");
            assertThat(result.page()).isEqualTo(page);
            assertThat(result.limit()).isEqualTo(2);
            assertThat(result.totalElements()).isEqualTo(5);
            assertThat(result.totalPages()).isEqualTo(3);
            visited.addAll(ids(result));
        }
        assertThat(visited).containsExactlyElementsOf(expectedOrder);
        assertThat(api.search("page=3&limit=2").content()).isEmpty();
    }

    /**
     * Lanza {@value #CONCURRENT_REQUESTS} POST a la vez, ejecuta {@code whileHeld} mientras el proveedor las
     * retiene y luego abre la compuerta para recoger todas las respuestas.
     */
    private List<HttpResult> sendConcurrently(IntFunction<String> idempotencyKey, AtomicInteger completed,
                                              ThrowingRunnable whileHeld) throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResult>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                String key = idempotencyKey.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    HttpResult result = api.post(SPEC_REQUEST, key);
                    completed.incrementAndGet();
                    return result;
                }));
            }
            assertThat(ready.await(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            start.countDown();
            try {
                whileHeld.run();
            } finally {
                // También si whileHeld falla: el executor no se cierra hasta que todas las requests respondan.
                GATE.open();
            }
            List<HttpResult> results = new ArrayList<>();
            for (Future<HttpResult> future : futures) {
                results.add(future.get(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return results;
        }
    }

    private TransactionResponse create(String accountId, String type, ResponseDefinitionBuilder providerResponse) {
        stubProvider(providerResponse);
        HttpResult result = api.post(request(accountId, type, "1500.00", "MXN"), null);
        assertThat(result.status()).isEqualTo(201);
        return result.transaction();
    }

    private static List<UUID> ids(PageResponse<TransactionResponse> page) {
        return page.content().stream().map(TransactionResponse::id).toList();
    }

    // Sondeo cada 10 ms desde el inicio: la espera dura lo que tarda la condición, no un intervalo fijo.
    private static ConditionFactory awaitAtMost(Duration timeout) {
        return Awaitility.await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10)).atMost(timeout);
    }

    private static void stubProvider(ResponseDefinitionBuilder response) {
        PROVIDER.stubFor(post(urlEqualTo(EXECUTE_PATH)).willReturn(response));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
