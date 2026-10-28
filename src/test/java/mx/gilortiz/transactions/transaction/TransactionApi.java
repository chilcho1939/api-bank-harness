package mx.gilortiz.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import mx.gilortiz.transactions.common.web.PageResponse;
import mx.gilortiz.transactions.transaction.dto.TransactionResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente HTTP real de {@code /transactions} contra el servidor embebido de un {@code *IT} ({@code RANDOM_PORT}).
 */
final class TransactionApi {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    // Tope para que un test nunca se cuelgue esperando al servidor.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient client;
    private final JsonMapper mapper;

    TransactionApi(int port, JsonMapper mapper) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();
        this.mapper = mapper;
    }

    /**
     * @param idempotencyKey {@code null} para no enviar el header
     */
    HttpResult post(String body, String idempotencyKey) {
        return client.post()
                .uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (idempotencyKey != null) {
                        headers.set(IDEMPOTENCY_KEY, idempotencyKey);
                    }
                })
                .body(body)
                .exchange((request, response) -> new HttpResult(mapper, response.getStatusCode().value(),
                        String.valueOf(response.getHeaders().getContentType()),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    PageResponse<TransactionResponse> search(String query) {
        String body = client.get()
                .uri("/transactions?" + query)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
        return mapper.readValue(body, new TypeReference<PageResponse<TransactionResponse>>() { });
    }

    record HttpResult(JsonMapper mapper, int status, String contentType, String body) {

        TransactionResponse transaction() {
            return mapper.readValue(body, TransactionResponse.class);
        }

        JsonNode json() {
            return mapper.readTree(body);
        }
    }
}
