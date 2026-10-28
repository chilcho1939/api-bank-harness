package mx.gilortiz.transactions.provider.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import mx.gilortiz.transactions.transaction.ProviderRequest;
import mx.gilortiz.transactions.transaction.ProviderResult;
import mx.gilortiz.transactions.transaction.TransactionProvider;
import mx.gilortiz.transactions.transaction.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProviderClientConfigurationTest {

    private static final ProviderRequest DEBIT_REQUEST = new ProviderRequest(
            "acc-123456", TransactionType.DEBIT, new BigDecimal("100.00"), "MXN");

    @RegisterExtension
    static final WireMockExtension PROVIDER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(ProviderClientConfiguration.class);

    @Test
    @DisplayName("application.yaml define base-url, connect-timeout 1s, read-timeout 3s y retry con backoff por defecto")
    void bindsDefaultsFromApplicationYaml() {
        contextRunner.run(context -> {
            ProviderProperties properties = context.getBean(ProviderProperties.class);

            assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:8089"));
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.retry()).isEqualTo(
                    new ProviderProperties.Retry(3, Duration.ofMillis(200), 2, 0.5));
            assertThat(context.getBean(TransactionProvider.class)).isInstanceOf(HttpTransactionProvider.class);
        });
    }

    @Test
    @DisplayName("base-url y read-timeout configurados se aplican al cliente: un proveedor lento da resultado desconocido")
    void appliesConfiguredBaseUrlAndReadTimeout() {
        PROVIDER.stubFor(post(urlEqualTo("/provider/v1/execute"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));

        // read-timeout (1s) muy por debajo del default (3s) y del delay (2s); holgado para runners de CI lentos.
        contextRunner
                .withPropertyValues(
                        "provider.base-url=" + PROVIDER.baseUrl(),
                        "provider.connect-timeout=500ms",
                        "provider.read-timeout=1s")
                .run(context -> {
                    HttpTransactionProvider provider = context.getBean(HttpTransactionProvider.class);

                    ProviderResult result = provider.execute(DEBIT_REQUEST);

                    assertThat(result).isInstanceOfSatisfying(ProviderResult.Unknown.class, unknown ->
                            assertThat(unknown.code()).isEqualTo(HttpTransactionProvider.PROVIDER_TIMEOUT));
                    // Cada intento pasa por el circuito: una sola llamada registrada = sin reintento (determinista).
                    assertThat(provider.circuitBreaker().getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
                    // WireMock puede registrar la petición recién al cumplir el delay: espera acotada, no un assert inmediato.
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                            PROVIDER.verify(1, postRequestedFor(urlEqualTo("/provider/v1/execute"))));
                });
    }

    @Test
    @DisplayName("el adaptador parte del RestClient.Builder de Boot: sus customizers llegan a la petición")
    void usesBootRestClientBuilder() {
        PROVIDER.stubFor(post(urlEqualTo("/provider/v1/execute")).willReturn(aResponse().withStatus(503)));

        contextRunner
                .withPropertyValues("provider.base-url=" + PROVIDER.baseUrl())
                .withBean(RestClientCustomizer.class,
                        () -> builder -> builder.defaultHeader("X-Boot-Customizer", "applied"))
                .run(context -> {
                    context.getBean(TransactionProvider.class).execute(DEBIT_REQUEST);

                    PROVIDER.verify(1, postRequestedFor(urlEqualTo("/provider/v1/execute"))
                            .withHeader("X-Boot-Customizer", equalTo("applied")));
                });
    }

    @Test
    @DisplayName("read-timeout no mayor que connect-timeout impide arrancar con un mensaje que nombra la propiedad")
    void failsToStartWhenReadTimeoutNotGreaterThanConnectTimeout() {
        contextRunner
                .withPropertyValues("provider.connect-timeout=3s", "provider.read-timeout=3s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("provider.read-timeout"));
    }
}
