package mx.gilortiz.transactions.transaction;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static mx.gilortiz.transactions.transaction.ProviderStubs.APPROVED_BODY;
import static mx.gilortiz.transactions.transaction.ProviderStubs.EXECUTE_PATH;
import static mx.gilortiz.transactions.transaction.ProviderStubs.SPEC_REQUEST;
import static mx.gilortiz.transactions.transaction.ProviderStubs.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import mx.gilortiz.transactions.DatabaseCleaner;
import mx.gilortiz.transactions.PostgresTestConfiguration;
import mx.gilortiz.transactions.transaction.TransactionApi.HttpResult;
import mx.gilortiz.transactions.transaction.dto.TransactionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Timeout de lectura del proveedor de punta a punta. Contexto propio con un read-timeout corto
 * ({@value #PROVIDER_READ_TIMEOUT}) para que el escenario sea rápido sin recortar la holgura de
 * {@link TransactionFlowIT}. La compuerta no se abre durante el POST: el proveedor nunca responde a tiempo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "provider.connect-timeout=250ms",
        "provider.read-timeout=" + TransactionTimeoutIT.PROVIDER_READ_TIMEOUT})
@Import(PostgresTestConfiguration.class)
class TransactionTimeoutIT {

    static final String PROVIDER_READ_TIMEOUT = "1s";

    private static final ProviderGate GATE = new ProviderGate();

    @RegisterExtension
    static final WireMockExtension PROVIDER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().extensions(GATE))
            .build();

    private final TransactionApi api;
    private final TransactionRepository repository;
    private final DatabaseCleaner databaseCleaner;

    @Autowired
    TransactionTimeoutIT(@LocalServerPort int port, JsonMapper mapper, TransactionRepository repository,
                         DatabaseCleaner databaseCleaner) {
        this.api = new TransactionApi(port, mapper);
        this.repository = repository;
        this.databaseCleaner = databaseCleaner;
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

    @AfterEach
    void releaseHeldRequests() {
        GATE.open();
    }

    @Test
    @DisplayName("un timeout de lectura del proveedor responde 201 UNKNOWN, persiste UNKNOWN y no se reintenta")
    void readTimeoutIsPersistedAsUnknown() {
        PROVIDER.stubFor(post(urlEqualTo(EXECUTE_PATH))
                .willReturn(json(200, APPROVED_BODY).withTransformers(ProviderGate.NAME)));

        HttpResult result = api.post(SPEC_REQUEST, null);

        assertThat(result.status()).isEqualTo(201);
        TransactionResponse response = result.transaction();
        assertThat(response.status()).isEqualTo(TransactionStatus.UNKNOWN);
        Transaction stored = repository.findById(response.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(TransactionStatus.UNKNOWN);
        assertThat(stored.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(stored.providerTransactionId()).isNull();
        assertThat(stored.balanceAfter()).isNull();
        // La compuerta cuenta al recibir: un reintento habría llegado antes de que la API respondiera.
        assertThat(GATE.arrivals()).as("llamadas que llegaron al proveedor").isEqualTo(1);
    }
}
